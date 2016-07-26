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

package org.casia.cripac.isee.vpe.alg;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.storage.StorageLevel;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaPairReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka.KafkaUtils;
import org.casia.cripac.isee.pedestrian.tracking.PedestrianTracker;
import org.casia.cripac.isee.pedestrian.tracking.Track;
import org.casia.cripac.isee.vpe.common.BroadcastSingleton;
import org.casia.cripac.isee.vpe.common.ByteArrayFactory;
import org.casia.cripac.isee.vpe.common.ByteArrayFactory.ByteArrayQueueParts;
import org.casia.cripac.isee.vpe.common.KafkaProducerFactory;
import org.casia.cripac.isee.vpe.common.ObjectFactory;
import org.casia.cripac.isee.vpe.common.ObjectSupplier;
import org.casia.cripac.isee.vpe.common.SparkStreamingApp;
import org.casia.cripac.isee.vpe.common.SynthesizedLogger;
import org.casia.cripac.isee.vpe.common.SynthesizedLoggerFactory;
import org.casia.cripac.isee.vpe.common.SystemPropertyCenter;
import org.casia.cripac.isee.vpe.ctrl.MetadataSavingApp;
import org.casia.cripac.isee.vpe.ctrl.TopicManager;
import org.casia.cripac.isee.vpe.debug.FakePedestrianTracker;
import org.xml.sax.SAXException;

import kafka.serializer.DefaultDecoder;
import kafka.serializer.StringDecoder;
import scala.Tuple2;

/**
 * The PedestrianTrackingApp class takes in video URLs from Kafka,
 * then process the videos with pedestrian tracking algorithms,
 * and finally push the tracking results back to Kafka.
 * 
 * @author Ken Yu, CRIPAC, 2016
 *
 */
public class PedestrianTrackingApp extends SparkStreamingApp {
	
	private static final long serialVersionUID = 3104859533881615664L;
//	private HashSet<String> taskTopicsSet = new HashSet<>();
	private Map<String, Integer> taskTopicPartitions = new HashMap<>();
	private Properties trackProducerProperties = new Properties();
	private transient SparkConf sparkConf;
	private Map<String, String> commonKafkaParams = new HashMap<>();
	private boolean verbose = false;
	
	String messageListenerAddr;
	int messageListenerPort;

	public static final String APPLICATION_NAME = "PedestrianTracking";
	public static final String PEDESTRIAN_TRACKING_TASK_TOPIC = "tracking-task";

	public PedestrianTrackingApp(SystemPropertyCenter propertyCenter) {
		super();
		
		verbose = propertyCenter.verbose;
		
		messageListenerAddr = propertyCenter.messageListenerAddress;
		messageListenerPort = propertyCenter.messageListenerPort;
		
//		taskTopicsSet.add(PEDESTRIAN_TRACKING_TASK_TOPIC);
		taskTopicPartitions.put(PEDESTRIAN_TRACKING_TASK_TOPIC, propertyCenter.kafkaPartitions);
		
		trackProducerProperties.put("bootstrap.servers", propertyCenter.kafkaBrokers);
		trackProducerProperties.put("producer.type", "sync");
		trackProducerProperties.put("request.required.acks", "1");
		trackProducerProperties.put("compression.codec", "gzip");
		trackProducerProperties.put(
				"key.serializer", "org.apache.kafka.common.serialization.StringSerializer"); 
		trackProducerProperties.put(
				"value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
		
		SynthesizedLogger logger = new SynthesizedLogger(messageListenerAddr, messageListenerPort);
		logger.info("MessageHandlingApp: spark.dynamic.allocation.enabled="
				+ propertyCenter.sparkDynamicAllocationEnabled);
		logger.info("MessageHandlingApp: spark.streaming.dynamic.allocation.enabled="
				+ propertyCenter.sparkStreamingDynamicAllocationEnabled);
		logger.info("MessageHandlingApp: spark.streaming.dynamicAllocation.minExecutors="
				+ propertyCenter.sparkStreamingDynamicAllocationMinExecutors);
		logger.info("MessageHandlingApp: spark.streaming.dynamicAllocation.maxExecutors="
				+ propertyCenter.sparkStreamingDynamicAllocationMaxExecutors);

		//Create contexts.
		sparkConf = new SparkConf()
				.setAppName(APPLICATION_NAME)
				.set("spark.rdd.compress", "true")
				.set("spark.streaming.receiver.writeAheadLog.enable", "true")
				.set("spark.streaming.driver.writeAheadLog.closeFileAfterWrite", "true")
				.set("spark.streaming.receiver.writeAheadLog.closeFileAfterWrite", "true");
		
		if (!propertyCenter.onYARN) {
			sparkConf = sparkConf
					.setMaster(propertyCenter.sparkMaster)
					.set("deploy.mode", propertyCenter.sparkDeployMode);
		}

		commonKafkaParams.put("metadata.broker.list", propertyCenter.kafkaBrokers);
		commonKafkaParams.put("group.id", "PedestrianTrackingApp" + UUID.randomUUID());
		commonKafkaParams.put("zookeeper.connect", propertyCenter.zookeeperConnect);
		// Determine where the stream starts (default: largest)
		commonKafkaParams.put("auto.offset.reset", "smallest");
		commonKafkaParams.put("fetch.message.max.bytes", "" + propertyCenter.kafkaFetchMessageMaxBytes);
	}

	@Override
	protected JavaStreamingContext getStreamContext() {
		//Create contexts.	
		JavaSparkContext sparkContext = new JavaSparkContext(sparkConf);
		sparkContext.setLocalProperty("spark.scheduler.pool", "vpe");
		JavaStreamingContext streamingContext = new JavaStreamingContext(sparkContext, Durations.seconds(2));

		//Create KafkaSink for Spark Streaming to output to Kafka.
		final BroadcastSingleton<KafkaProducer<String, byte[]>> producerSingleton =
				new BroadcastSingleton<KafkaProducer<String, byte[]>>(
						new KafkaProducerFactory<>(trackProducerProperties),
						KafkaProducer.class);
		//Create ResourceSink for any other unserializable components.
		final BroadcastSingleton<PedestrianTracker> trackerSingleton =
				new BroadcastSingleton<PedestrianTracker>(new ObjectFactory<PedestrianTracker>() {

					private static final long serialVersionUID = -3454317350293711609L;

					@Override
					public PedestrianTracker getObject() {
						return new FakePedestrianTracker();
					}
				}, PedestrianTracker.class);
		final BroadcastSingleton<SynthesizedLogger> loggerSingleton =
				new BroadcastSingleton<>(
						new SynthesizedLoggerFactory(messageListenerAddr, messageListenerPort),
						SynthesizedLogger.class);

		/**
		 * Though the "createDirectStream" method is suggested for higher speed,
		 * we use createStream for auto management of Kafka offsets by Zookeeper.
		 * TODO Create multiple input streams and unite them together for higher receiving speed.
		 * @link http://spark.apache.org/docs/latest/streaming-programming-guide.html#level-of-parallelism-in-data-receiving
		 * TODO Find ways to robustly make use of createDirectStream.
		 */
		JavaPairReceiverInputDStream<String, byte[]> taskDStream = KafkaUtils.createStream(
				streamingContext,
				String.class, byte[].class, StringDecoder.class, DefaultDecoder.class,
				commonKafkaParams,
				taskTopicPartitions, 
				StorageLevel.MEMORY_AND_DISK_SER());
//		//Retrieve messages from Kafka.
//		JavaPairInputDStream<String, byte[]> taskDStream =
//				KafkaUtils.createDirectStream(streamingContext, String.class, byte[].class,
//				StringDecoder.class, DefaultDecoder.class, commonKafkaParams, taskTopicsSet);
		
		taskDStream.foreachRDD(new VoidFunction<JavaPairRDD<String, byte[]>>() {

			private static final long serialVersionUID = -6015951200762719085L;

			@Override
			public void call(JavaPairRDD<String, byte[]> taskRDD) throws Exception {

				final ObjectSupplier<KafkaProducer<String, byte[]>> producerSupplier =
						producerSingleton.getSupplier(new JavaSparkContext(taskRDD.context()));
				final ObjectSupplier<PedestrianTracker> trackerSupplier =
						trackerSingleton.getSupplier(new JavaSparkContext(taskRDD.context()));
				final ObjectSupplier<SynthesizedLogger> loggerSupplier = 
						loggerSingleton.getSupplier(new JavaSparkContext(taskRDD.context()));

				taskRDD.context().setLocalProperty("spark.scheduler.pool", "vpe");
				taskRDD.foreach(new VoidFunction<Tuple2<String,byte[]>>() {

					private static final long serialVersionUID = 955383087048954689L;

					@Override
					public void call(Tuple2<String, byte[]> task) throws Exception {
						
						String execQueue = task._1();
						byte[] dataQueue = ByteArrayFactory.decompress(task._2());
						ByteArrayQueueParts parts = ByteArrayFactory.splitByteStream(dataQueue);
						String videoURL = (String) ByteArrayFactory.getObject(parts.head);
						byte[] restDataQueue = parts.rest;
						
						Set<Track> tracks = trackerSupplier.get().track(videoURL);

						UUID taskID = UUID.randomUUID();
						
						for (Track track : tracks) {
							// Fill in this field here, so track generators do not need to consider about it.
							track.taskID = taskID;
							
							// Append the track to the dataQueue.
							byte[] trackBytes = ByteArrayFactory.getByteArray(track);
							byte[] compressedTrackBytes = ByteArrayFactory.compress(trackBytes);
							byte[] newDataQueue = ByteArrayFactory.combineByteArray(
									ByteArrayFactory.appendLengthToHead(trackBytes), restDataQueue);
							byte[] compressedDataQueue = ByteArrayFactory.compress(newDataQueue);

							if (execQueue.length() > 0) {
								// Extract current execution queue.
								String curExecQueue;
								String restExecQueue;
								int splitIndex = execQueue.indexOf('|');
								if (splitIndex == -1) {
									curExecQueue = execQueue;
									restExecQueue = "";
								} else {
									curExecQueue = execQueue.substring(0, splitIndex);
									restExecQueue = execQueue.substring(splitIndex + 1);
								}
								
								String[] topics = curExecQueue.split(",");
								
								//Send to each topic.
								for (String topic : topics) {
									if (verbose) {
										loggerSupplier.get().info("PedestrianTrackingApp: Sending to Kafka: <" +
												topic + ">" + restExecQueue + "=" + "A track");
									}
									producerSupplier.get().send(
											new ProducerRecord<String, byte[]>(
													topic,
													restExecQueue,
													compressedDataQueue));
								}
							}
							
							//Always send to the meta data saving application.
							if (verbose) {
								loggerSupplier.get().info("PedestrianTrackingApp: Sending to Kafka: <" +
										MetadataSavingApp.PEDESTRIAN_TRACK_SAVING_INPUT_TOPIC + ">" + "A track");
							}
							producerSupplier.get().send(
									new ProducerRecord<String, byte[]>(
											MetadataSavingApp.PEDESTRIAN_TRACK_SAVING_INPUT_TOPIC, 
											compressedTrackBytes));
						}
						
						System.gc();
					}
				});
			}
		});
		
		return streamingContext;
	}

	/**
	 * @param args No options supported currently.
	 * @throws URISyntaxException 
	 * @throws SAXException 
	 * @throws ParserConfigurationException 
	 */
	public static void main(String[] args) throws URISyntaxException, ParserConfigurationException, SAXException {
		//Load system properties.
		SystemPropertyCenter propertyCenter;
		propertyCenter = new SystemPropertyCenter(args);
		
		if (propertyCenter.verbose) {
			System.out.println("Starting PedestrianTrackingApp...");
		}
		
		TopicManager.checkTopics(propertyCenter);
		
		//Start the pedestrian tracking application.
		PedestrianTrackingApp pedestrianTrackingApp = new PedestrianTrackingApp(propertyCenter);
		pedestrianTrackingApp.initialize(propertyCenter);
		pedestrianTrackingApp.start();
		pedestrianTrackingApp.awaitTermination();
	}
}
