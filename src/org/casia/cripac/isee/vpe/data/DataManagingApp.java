/***********************************************************************
 * This file is part of VPE-Platform.
 * 
 * VPE-Platform is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * VPE-Platform is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with VPE-Platform.  If not, see <http://www.gnu.org/licenses/>.
 ************************************************************************/

package org.casia.cripac.isee.vpe.data;

import static org.bytedeco.javacpp.opencv_core.CV_8UC3;
import static org.bytedeco.javacpp.opencv_imgcodecs.imencode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Future;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.tools.HadoopArchives;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacpp.opencv_core.Mat;
import org.bytedeco.javacpp.opencv_imgproc;
import org.bytedeco.javacpp.helper.opencv_core;
import org.casia.cripac.isee.pedestrian.attr.Attributes;
import org.casia.cripac.isee.pedestrian.reid.PedestrianInfo;
import org.casia.cripac.isee.pedestrian.tracking.Track;
import org.casia.cripac.isee.pedestrian.tracking.Track.BoundingBox;
import org.casia.cripac.isee.vpe.common.BroadcastSingleton;
import org.casia.cripac.isee.vpe.common.KafkaProducerFactory;
import org.casia.cripac.isee.vpe.common.ObjectFactory;
import org.casia.cripac.isee.vpe.common.ObjectSupplier;
import org.casia.cripac.isee.vpe.common.SerializationHelper;
import org.casia.cripac.isee.vpe.common.SparkStreamingApp;
import org.casia.cripac.isee.vpe.common.SystemPropertyCenter;
import org.casia.cripac.isee.vpe.ctrl.TaskData;
import org.casia.cripac.isee.vpe.ctrl.TopicManager;
import org.casia.cripac.isee.vpe.debug.FakeDatabaseConnector;
import org.casia.cripac.isee.vpe.util.logging.SynthesizedLogger;
import org.casia.cripac.isee.vpe.util.logging.SynthesizedLoggerFactory;
import org.xml.sax.SAXException;

import scala.Tuple2;

/**
 * The DataManagingApp class combines two functions: meta data saving and data
 * feeding. The meta data saving function saves meta data, which may be the
 * results of vision algotirhms, to HDFS and Neo4j database. The data feeding
 * function retrieves stored results and send them to algorithm modules from
 * HDFS and Neo4j database.
 * 
 * @author Ken Yu, CRIPAC, 2016
 *
 */
public class DataManagingApp extends SparkStreamingApp {

	private static final long serialVersionUID = -4167212422997458537L;

	/**
	 * The name of this application.
	 */
	public static final String APP_NAME = "DataManaging";

	// Topics for feeding.
	public static final String PEDESTRIAN_TRACK_RTRV_JOB_TOPIC = "pedestrian-track-rtrv-job";
	public static final String PEDESTRIAN_TRACK_WITH_ATTR_RTRV_JOB_TOPIC = "pedestrian-track-with-attr-rtrv-job";

	// Topics for saving.
	public static final String PEDESTRIAN_TRACK_FOR_SAVING_TOPIC = "pedestrian-track-for-saving";
	public static final String PEDESTRIAN_ATTR_FOR_SAVING_TOPIC = "pedestrian-attr-for-saving";
	public static final String PEDESTRIAN_ID_FOR_SAVING_TOPIC = "pedestrian-id-for-saving";

	/**
	 * Register these topics to the TopicManager, so that on the start of the
	 * whole system, the TopicManager can help register the topics this
	 * application needs to Kafka brokers.
	 */
	static {
		TopicManager.registerTopic(PEDESTRIAN_TRACK_RTRV_JOB_TOPIC);
		TopicManager.registerTopic(PEDESTRIAN_TRACK_WITH_ATTR_RTRV_JOB_TOPIC);

		TopicManager.registerTopic(PEDESTRIAN_TRACK_FOR_SAVING_TOPIC);
		TopicManager.registerTopic(PEDESTRIAN_ATTR_FOR_SAVING_TOPIC);
		TopicManager.registerTopic(PEDESTRIAN_ID_FOR_SAVING_TOPIC);
	}

	private Map<String, Integer> trackRtrvJobTopicMap = new HashMap<>();
	private Map<String, Integer> trackWithAttrRtrvJobTopicMap = new HashMap<>();

	private Map<String, Integer> trackForSavingTopicMap = new HashMap<>();
	private Map<String, Integer> attrForSavingTopicMap = new HashMap<>();
	private Map<String, Integer> idRankForSavingTopicMap = new HashMap<>();

	private Properties producerProperties = null;
	private transient SparkConf sparkConf;
	private Map<String, String> kafkaParams = new HashMap<>();
	private String metadataDir;
	private boolean verbose = false;
	private String reportListenerAddr;
	private int reportListenerPort;
	private int numRecvStreams;

	public DataManagingApp(SystemPropertyCenter propertyCenter)
			throws IOException, IllegalArgumentException, ParserConfigurationException, SAXException {

		verbose = propertyCenter.verbose;

		reportListenerAddr = propertyCenter.reportListenerAddress;
		reportListenerPort = propertyCenter.reportListenerPort;

		numRecvStreams = propertyCenter.numRecvStreams;

		trackRtrvJobTopicMap.put(PEDESTRIAN_TRACK_RTRV_JOB_TOPIC, propertyCenter.kafkaPartitions);
		trackWithAttrRtrvJobTopicMap.put(PEDESTRIAN_TRACK_WITH_ATTR_RTRV_JOB_TOPIC, propertyCenter.kafkaPartitions);

		trackForSavingTopicMap.put(PEDESTRIAN_TRACK_FOR_SAVING_TOPIC, propertyCenter.kafkaPartitions);
		attrForSavingTopicMap.put(PEDESTRIAN_ATTR_FOR_SAVING_TOPIC, propertyCenter.kafkaPartitions);
		idRankForSavingTopicMap.put(PEDESTRIAN_ID_FOR_SAVING_TOPIC, propertyCenter.kafkaPartitions);

		// Create contexts.
		sparkConf = new SparkConf().setAppName(APP_NAME).set("spark.rdd.compress", "true")
				.set("spark.streaming.receiver.writeAheadLog.enable", "true")
				.set("spark.streaming.driver.writeAheadLog.closeFileAfterWrite", "true")
				.set("spark.streaming.receiver.writeAheadLog.closeFileAfterWrite", "true");

		if (!propertyCenter.onYARN) {
			sparkConf = sparkConf.setMaster(propertyCenter.sparkMaster).set("deploy.mode",
					propertyCenter.sparkDeployMode);
		}

		producerProperties = new Properties();
		producerProperties.put("bootstrap.servers", propertyCenter.kafkaBrokers);
		producerProperties.put("compression.codec", "1");
		producerProperties.put("max.request.size", "10000000");
		producerProperties.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
		producerProperties.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");

		// Common Kafka settings
		kafkaParams.put("group.id", "MetadataSavingApp" + UUID.randomUUID());
		kafkaParams.put("zookeeper.connect", propertyCenter.zookeeperConnect);
		kafkaParams.put("metadata.broker.list", propertyCenter.kafkaBrokers);
		// Determine where the stream starts (default: largest)
		kafkaParams.put("auto.offset.reset", "smallest");
		kafkaParams.put("fetch.message.max.bytes", "" + propertyCenter.kafkaFetchMessageMaxBytes);

		metadataDir = propertyCenter.metadataDir;
	}

	private void setupDataFeeding(JavaStreamingContext streamingContext,
			final BroadcastSingleton<KafkaProducer<String, byte[]>> broadcastKafkaSink,
			final BroadcastSingleton<SynthesizedLogger> loggerSingleton) {
		FakeDatabaseConnector databaseConnector = new FakeDatabaseConnector();

		// Read track retrieving jobs in parallel from Kafka.
		buildBytesDirectInputStream(streamingContext, numRecvStreams, kafkaParams, trackRtrvJobTopicMap)
				// Retrieve and deliever tracks.
				.foreachRDD(new VoidFunction<JavaPairRDD<String, byte[]>>() {

					private static final long serialVersionUID = 2398785978507302303L;

					@Override
					public void call(JavaPairRDD<String, byte[]> jobRDD) throws Exception {

						final ObjectSupplier<KafkaProducer<String, byte[]>> producerSupplier = broadcastKafkaSink
								.getSupplier(new JavaSparkContext(jobRDD.context()));
						final ObjectSupplier<SynthesizedLogger> loggerSupplier = loggerSingleton
								.getSupplier(new JavaSparkContext(jobRDD.context()));

						jobRDD.context().setLocalProperty("spark.scheduler.pool", "vpe");
						jobRDD.foreach(new VoidFunction<Tuple2<String, byte[]>>() {

							private static final long serialVersionUID = -3787928455732734520L;

							@Override
							public void call(Tuple2<String, byte[]> job) throws Exception {
								TaskData taskData = (TaskData) SerializationHelper.deserialize(job._2());
								String jobParam = (String) taskData.predecessorResult;
								String[] paramParts = jobParam.split(":");
								Track track = databaseConnector.getTrack(paramParts[0], paramParts[1]);
								taskData.predecessorResult = track;

								// Get the IDs of successor nodes.
								int[] successorIDs = taskData.executionPlan.getNode(taskData.currentNodeID)
										.getSuccessors();
								// Mark the current node as executed.
								taskData.executionPlan.markExecuted(taskData.currentNodeID);
								// Send to all the successor nodes.
								for (int successorID : successorIDs) {
									taskData.currentNodeID = successorID;
									String topic = taskData.executionPlan.getNode(successorID).getTopic();

									Future<RecordMetadata> future = producerSupplier.get()
											.send(new ProducerRecord<String, byte[]>(topic, job._1(),
													SerializationHelper.serialize(taskData)));
									RecordMetadata metadata = future.get();
									if (verbose) {
										loggerSupplier.get()
												.info(APP_NAME + ": Sent to Kafka <" + metadata.topic() + "-"
														+ metadata.partition() + "-" + metadata.offset() + ">: "
														+ job._1() + ": " + taskData);
									}
								}
							}
						});
					}
				});

		// Read track with attributes retrieving jobs in parallel from Kafka.
		buildBytesDirectInputStream(streamingContext, numRecvStreams, kafkaParams, trackWithAttrRtrvJobTopicMap)
				// Retrieve and deliever tracks with attributes.
				.foreachRDD(new VoidFunction<JavaPairRDD<String, byte[]>>() {

					private static final long serialVersionUID = 2398785978507302303L;

					@Override
					public void call(JavaPairRDD<String, byte[]> jobRDD) throws Exception {

						final ObjectSupplier<KafkaProducer<String, byte[]>> producerSupplier = broadcastKafkaSink
								.getSupplier(new JavaSparkContext(jobRDD.context()));
						final ObjectSupplier<SynthesizedLogger> loggerSupplier = loggerSingleton
								.getSupplier(new JavaSparkContext(jobRDD.context()));

						jobRDD.context().setLocalProperty("spark.scheduler.pool", "vpe");
						jobRDD.foreach(new VoidFunction<Tuple2<String, byte[]>>() {

							private static final long serialVersionUID = -3787928455732734520L;

							@Override
							public void call(Tuple2<String, byte[]> job) throws Exception {
								TaskData taskData = (TaskData) SerializationHelper.deserialize(job._2());
								String jobParam = (String) taskData.predecessorResult;
								String[] paramParts = jobParam.split(":");
								PedestrianInfo trackWithAttr = databaseConnector.getTrackWithAttr(paramParts[0],
										paramParts[1]);
								taskData.predecessorResult = trackWithAttr;

								// Get the IDs of successor nodes.
								int[] successorIDs = taskData.executionPlan.getNode(taskData.currentNodeID)
										.getSuccessors();
								// Mark the current node as executed.
								taskData.executionPlan.markExecuted(taskData.currentNodeID);
								// Send to all the successor nodes.
								for (int successorID : successorIDs) {
									taskData.currentNodeID = successorID;
									String topic = taskData.executionPlan.getNode(successorID).getTopic();

									Future<RecordMetadata> future = producerSupplier.get()
											.send(new ProducerRecord<String, byte[]>(topic, job._1(),
													SerializationHelper.serialize(taskData)));
									RecordMetadata metadata = future.get();
									if (verbose) {
										loggerSupplier.get()
												.info(APP_NAME + ": Sent to Kafka <" + metadata.topic() + "-"
														+ metadata.partition() + "-" + metadata.offset() + "> :"
														+ job._1() + ": " + taskData);
									}
								}
							}
						});
					}
				});
	}

	private void setupMetadataSaving(JavaStreamingContext streamingContext,
			final BroadcastSingleton<FileSystem> fileSystemSingleton,
			final BroadcastSingleton<SynthesizedLogger> loggerSingleton) {
		// Save tracks.
		buildBytesDirectInputStream(streamingContext, numRecvStreams, kafkaParams, trackForSavingTopicMap).groupByKey()
				.foreachRDD(new VoidFunction<JavaPairRDD<String, Iterable<byte[]>>>() {

					private static final long serialVersionUID = -6731502755371825010L;

					@Override
					public void call(JavaPairRDD<String, Iterable<byte[]>> trackGroupRDD) throws Exception {

						final ObjectSupplier<FileSystem> fsSupplier = fileSystemSingleton
								.getSupplier(new JavaSparkContext(trackGroupRDD.context()));
						final ObjectSupplier<SynthesizedLogger> loggerSupplier = loggerSingleton
								.getSupplier(new JavaSparkContext(trackGroupRDD.context()));

						trackGroupRDD.context().setLocalProperty("spark.scheduler.pool", "vpe");
						trackGroupRDD.foreach(new VoidFunction<Tuple2<String, Iterable<byte[]>>>() {

							private static final long serialVersionUID = 5522067102611597772L;

							@Override
							public void call(Tuple2<String, Iterable<byte[]>> trackGroup) throws Exception {
								// RuntimeException: No native JavaCPP library
								// in memory. (Has Loader.load() been called?)
								Loader.load(opencv_core.class);
								Loader.load(opencv_imgproc.class);

								FileSystem fs = fsSupplier.get();

								String taskID = trackGroup._1();
								Iterator<byte[]> trackIterator = trackGroup._2().iterator();
								Track track = (Track) SerializationHelper.deserialize(trackIterator.next());
								String videoURL = new String(track.videoURL);
								int numTracks = track.numTracks;
								String videoRoot = metadataDir + "/" + videoURL;
								String taskRoot = videoRoot + "/" + taskID;
								fs.mkdirs(new Path(taskRoot));

								while (true) {
									loggerSupplier.get()
											.info("Task " + videoURL + "-" + taskID + " got track: " + track.id + "!");

									String storeDir = taskRoot + "/" + track.id;
									fs.mkdirs(new Path(storeDir));

									int numBBoxes = track.locationSequence.length;

									// Write bounding boxes infomations.
									FSDataOutputStream outputStream = fs.create(new Path(storeDir + "/bbox.txt"));
									BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
									writer.write("{");
									writer.newLine();
									writer.write("\t\"startFrameIndex\":" + track.startFrameIndex);
									writer.newLine();
									writer.write("\t\"boundingBoxes\":[");

									for (int i = 0; i < numBBoxes; ++i) {
										BoundingBox bbox = track.locationSequence[i];

										writer.write("{");
										writer.newLine();
										writer.write("\t\t\"x\": " + bbox.x + ",");
										writer.newLine();
										writer.write("\t\t\"y\": " + bbox.y + ",");
										writer.newLine();
										writer.write("\t\t\"width\": " + bbox.width + ",");
										writer.newLine();
										writer.write("\t\t\"height\": " + bbox.height);
										writer.newLine();
										writer.write("\t}");
										if (i + 1 < numBBoxes) {
											writer.write(", ");
										}

										// Use JavaCV to encode the image patch
										// into a
										// JPEG, stored in the memory.
										BytePointer inputPointer = new BytePointer(bbox.patchData);
										Mat image = new Mat(bbox.height, bbox.width, CV_8UC3, inputPointer);
										BytePointer outputPointer = new BytePointer();
										imencode(".jpg", image, outputPointer);
										byte[] bytes = new byte[(int) outputPointer.limit()];
										outputPointer.get(bytes);

										// Output the image patch to HDFS.
										FSDataOutputStream imgOutputStream = fs
												.create(new Path(storeDir + "/" + i + ".jpg"));
										imgOutputStream.write(bytes);
										imgOutputStream.close();

										image.release();
										inputPointer.deallocate();
										outputPointer.deallocate();
									}

									writer.write("\t]");
									writer.newLine();
									writer.write("}");
									writer.newLine();
									writer.flush();
									writer.close();
									outputStream.close();

									if (!trackIterator.hasNext()) {
										break;
									}
									track = (Track) SerializationHelper.deserialize(trackIterator.next());
								}

								// If all the tracks from a task are saved,
								// it's time to pack them into a HAR!
								ContentSummary contentSummary = fs.getContentSummary(new Path(taskRoot));
								long cnt = contentSummary.getDirectoryCount();
								// Decrease one for directory counter.
								if (cnt - 1 == numTracks) {
									loggerSupplier.get().info("Task " + videoURL + "-" + taskID + " finished!");

									HadoopArchives archives = new HadoopArchives(new Configuration());
									ArrayList<String> options = new ArrayList<>();
									options.add("-archiveName");
									options.add(taskID + ".har");
									options.add("-p");
									options.add(taskRoot);
									options.add(videoRoot);
									archives.run(Arrays.copyOf(options.toArray(), options.size(), String[].class));

									loggerSupplier.get().info("Tracks of " + videoURL + "-" + taskID + " packed!");

									// Delete the original folder recursively.
									fs.delete(new Path(taskRoot), true);
								} else {
									loggerSupplier.get().info("Task " + videoURL + "-" + taskID + " need "
											+ (numTracks - cnt + 1) + "/" + numTracks + " more tracks!");
								}
							}
						});
					}
				});

		// Display the attributes.
		// TODO Modify the streaming steps from here to store the meta data.
		buildBytesDirectInputStream(streamingContext, numRecvStreams, kafkaParams, attrForSavingTopicMap)
				.foreachRDD(new VoidFunction<JavaPairRDD<String, byte[]>>() {

					private static final long serialVersionUID = -715024705240889905L;

					@Override
					public void call(JavaPairRDD<String, byte[]> attrRDD) throws Exception {

						final ObjectSupplier<SynthesizedLogger> loggerSupplier = loggerSingleton
								.getSupplier(new JavaSparkContext(attrRDD.context()));

						attrRDD.context().setLocalProperty("spark.scheduler.pool", "vpe");
						attrRDD.foreach(new VoidFunction<Tuple2<String, byte[]>>() {

							private static final long serialVersionUID = -4846631314801254257L;

							@Override
							public void call(Tuple2<String, byte[]> result) throws Exception {
								Attributes attr;
								try {
									attr = (Attributes) SerializationHelper.deserialize(result._2());

									if (verbose) {
										loggerSupplier.get()
												.info("Metadata saver received " + result._1() + ": " + attr);
									}
								} catch (IOException e) {
									loggerSupplier.get().error("Exception caught when decompressing attributes", e);
								}
							}

						});
					}
				});

		// Display the id ranks.
		// TODO Modify the streaming steps from here to store the meta data.
		buildBytesDirectInputStream(streamingContext, numRecvStreams, kafkaParams, idRankForSavingTopicMap)
				.foreachRDD(new VoidFunction<JavaPairRDD<String, byte[]>>() {

					private static final long serialVersionUID = -715024705240889905L;

					@Override
					public void call(JavaPairRDD<String, byte[]> attrRDD) throws Exception {

						final ObjectSupplier<SynthesizedLogger> loggerSupplier = loggerSingleton
								.getSupplier(new JavaSparkContext(attrRDD.context()));

						attrRDD.context().setLocalProperty("spark.scheduler.pool", "vpe");
						attrRDD.foreach(new VoidFunction<Tuple2<String, byte[]>>() {

							private static final long serialVersionUID = -4846631314801254257L;

							@Override
							public void call(Tuple2<String, byte[]> result) throws Exception {
								int[] idRank;
								try {
									idRank = (int[]) SerializationHelper.deserialize(result._2());
									if (verbose) {
										String rankStr = "";
										for (int id : idRank) {
											rankStr = rankStr + id + " ";
										}
										loggerSupplier.get().info("Metadata saver received: " + result._1()
												+ ": Pedestrian ID rank: " + rankStr);
									}
								} catch (IOException e) {
									loggerSupplier.get().error("Exception caught when decompressing ID", e);
								}
							}

						});
					}
				});
	}

	@Override
	protected JavaStreamingContext getStreamContext() {
		// Create contexts.
		JavaSparkContext sparkContext = new JavaSparkContext(sparkConf);
		sparkContext.setLocalProperty("spark.scheduler.pool", "vpe");
		JavaStreamingContext streamingContext = new JavaStreamingContext(sparkContext, Durations.seconds(2));

		// Create KafkaSink for Spark Streaming to output to Kafka.
		final BroadcastSingleton<KafkaProducer<String, byte[]>> broadcastKafkaSink = new BroadcastSingleton<>(
				new KafkaProducerFactory<String, byte[]>(producerProperties), KafkaProducer.class);

		final BroadcastSingleton<SynthesizedLogger> loggerSingleton = new BroadcastSingleton<>(
				new SynthesizedLoggerFactory(reportListenerAddr, reportListenerPort), SynthesizedLogger.class);

		final BroadcastSingleton<FileSystem> fileSystemSingleton = new BroadcastSingleton<>(
				new ObjectFactory<FileSystem>() {

					private static final long serialVersionUID = 300022787313821456L;

					@Override
					public FileSystem getObject() {
						Configuration hdfsConf;
						try {
							hdfsConf = new Configuration();
							hdfsConf.setBoolean("dfs.support.append", true);
							return FileSystem.get(hdfsConf);
						} catch (IOException e) {
							e.printStackTrace();
							return null;
						}
					}
				}, FileSystem.class);

		// Setup streams for data feeding.
		setupDataFeeding(streamingContext, broadcastKafkaSink, loggerSingleton);

		// Setup streams for meta data saving.
		setupMetadataSaving(streamingContext, fileSystemSingleton, loggerSingleton);

		return streamingContext;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.casia.cripac.isee.vpe.common.SparkStreamingApp#getAppName()
	 */
	@Override
	public String getAppName() {
		return APP_NAME;
	}

	public static void main(String[] args)
			throws IOException, URISyntaxException, ParserConfigurationException, SAXException {

		SystemPropertyCenter propertyCenter;
		if (args.length > 0) {
			propertyCenter = new SystemPropertyCenter(args);
		} else {
			propertyCenter = new SystemPropertyCenter();
		}

		if (propertyCenter.verbose) {
			System.out.println("Starting DataManagingApp...");
		}

		TopicManager.checkTopics(propertyCenter);

		DataManagingApp dataManagingApp = new DataManagingApp(propertyCenter);
		dataManagingApp.initialize(propertyCenter);
		dataManagingApp.start();
		dataManagingApp.awaitTermination();
	}
}
