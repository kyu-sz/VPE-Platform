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

package org.casia.cripac.isee.vpe.common;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map.Entry;
import java.util.Properties;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.casia.cripac.isee.vpe.alg.PedestrianAttrRecogApp;
import org.casia.cripac.isee.vpe.alg.PedestrianTrackingApp;
import org.casia.cripac.isee.vpe.ctrl.MessageHandlingApp;
import org.casia.cripac.isee.vpe.ctrl.MetadataSavingApp;
import org.casia.cripac.isee.vpe.debug.CommandGeneratingApp;
import org.xml.sax.SAXException;

/**
 * The SystemPropertyCenter class is responsible of managing the properties of the systems.
 * There are some properties predefined, and they can be overwritten by command options or an extern property file.
 * It can also generate back command options for uses like SparkSubmit.
 * @author Ken Yu, CRIPAC, 2016
 *
 */
public class SystemPropertyCenter {
	
	/**
	 * Thrown when no application is specified in any possible property sources.
	 * @author Ken Yu, CRIPAC, 2016
	 *
	 */
	public static class NoAppSpecifiedException extends Exception {
		private static final long serialVersionUID = -8356206863229009557L;
	}
	
	//Zookeeper properties
	public String zookeeperConnect = "localhost:2181";
	public int sessionTimeoutMs = 10 * 10000;
	public int connectionTimeoutMs = 8 * 1000;
	
	//Kafka properties
	public String kafkaBrokers = "localhost:9092";
	public int kafkaPartitions = 1;
	public int kafkaReplicationFactor = 1;
	public int kafkaFetchMessageMaxBytes = 10000000;
	
	//Spark properties
	public String checkpointDir = "checkpoint";
	public String sparkMaster = "local[*]";
	public String sparkDeployMode = "client";
	public String appName = "";
	public boolean onYARN = false;
	public String executorMem = "1G";	//Memory per executor (e.g. 1000M, 2G) (Default: 1G)
	public int numExecutors = 2; 		//Number of executors to start (Default: 2)
	public int executorCores = 1;		//Number of cores per executor (Default: 1)
	public String driverMem = "1G";		//Memory for driver (e.g. 1000M, 2G) (Default: 1024 Mb)
	public int driverCores = 1;			//Number of cores used by the driver (Default: 1).
	public String hadoopQueue = "default";	//The hadoop queue to use for allocation requests (Default: 'default')
	
	public String propertyFilePath = "";
	public String hdfsDefaultName = "localhost:9000";
	public String yarnResourceManagerHostname = "localhost";
	public String jarPath = "bin/vpe-platform.jar";
	
	public String sparkSchedulerMode = "FAIR";
	public String sparkShuffleServiceEnabled = "true";
	public String sparkDynamicAllocationEnabled = "true";
	public String sparkDynamicAllocationMinExecutors = "0";
	public String sparkDynamicAllocationMaxExecutors = "100";
	public String sparkStreamingDynamicAllocationEnabled = "true";
	public String sparkStreamingDynamicAllocationMinExecutors = "0";
	public String sparkStreamingDynamicAllocationMaxExecutors = "100";
	public String sparkStreamingDynamicAllocationDebug = "true";
	public String sparkStreamingDynamicAllocationDelayRounds = "5";

	public String messageListenerAddress = "localhost";
	public int messageListenerPort = 0;
	
	/**
	 * Whether to print verbose running information.
	 */
	public boolean verbose = false;
	
	/**
	 * Construction function supporting allocating a SystemPropertyCenter then filling in the properties manually.
	 */
	public SystemPropertyCenter() { }
	
	public String getMainClass() throws NoAppSpecifiedException {
		switch (appName) {
		case MessageHandlingApp.APPLICATION_NAME:
			return "org.casia.cripac.isee.vpe.ctrl.MessageHandlingApp";
		case MetadataSavingApp.APPLICATION_NAME:
			return "org.casia.cripac.isee.vpe.ctrl.MetadataSavingApp";
		case PedestrianAttrRecogApp.APPLICATION_NAME:
			return "org.casia.cripac.isee.vpe.alg.PedestrianAttrRecogApp";
		case PedestrianTrackingApp.APPLICATION_NAME:
			return "org.casia.cripac.isee.vpe.alg.PedestrianTrackingApp";
		case CommandGeneratingApp.APPLICATION_NAME:
			return "org.casia.cripac.isee.vpe.debug.CommandGeneratingApp";
		default:
			System.err.printf("No application named \"%s\"!\n", appName);
		case "":
			System.out.println("Try using '-h' for more information.");
			throw new NoAppSpecifiedException();
		}
	}
	
	/**
	 * Generate command line options for SparkSubmit client, according to the stored properties.
	 * @return An array of string with format required by SparkSubmit client.
	 * @throws NoAppSpecifiedException
	 */
	public String[] getArgs() throws NoAppSpecifiedException {
		ArrayList<String> options = new ArrayList<>();

		if (onYARN) {
			if (verbose) {
				options.add("-v");
			}

			options.add("-b");
			options.add(kafkaBrokers);

			options.add("-z");
			options.add(zookeeperConnect);

			options.add("-n");
			options.add(hdfsDefaultName);

			options.add("-p");
			options.add("" + kafkaPartitions);

			options.add("-r");
			options.add("" + kafkaReplicationFactor);

			options.add("--kafka-fetch-message-max-bytes");
			options.add("" + kafkaFetchMessageMaxBytes);

			options.add("-y");
			options.add(yarnResourceManagerHostname);

			options.add("-c");
			options.add(checkpointDir);
			
			options.add("--spark-scheduler-mode");
			options.add(sparkSchedulerMode);

			options.add("--spark-shuffle-service-enabled");
			options.add(sparkShuffleServiceEnabled);

			options.add("--spark-dynamicAllocation-enabled");
			options.add(sparkDynamicAllocationEnabled);

			options.add("--spark-dynamicAllocation-minExecutors");
			options.add(sparkDynamicAllocationMinExecutors);

			options.add("--spark-dynamicAllocation-maxExecutors");
			options.add(sparkDynamicAllocationMaxExecutors);

			options.add("--spark-streaming-dynamicAllocation-enabled");
			options.add(sparkStreamingDynamicAllocationEnabled);

			options.add("--spark-streaming-dynamicAllocation-minExecutors");
			options.add(sparkStreamingDynamicAllocationMinExecutors);

			options.add("--spark-streaming-dynamicAllocation-maxExecutors");
			options.add(sparkStreamingDynamicAllocationMaxExecutors);

			options.add("--spark-streaming-dynamicAllocation-debug");
			options.add(sparkStreamingDynamicAllocationDebug);

			options.add("--spark-streaming-dynamicAllocation-delay-rounds");
			options.add(sparkStreamingDynamicAllocationDelayRounds);
			
			options.add("--message-listening-addr");
			options.add(messageListenerAddress);
			
			options.add("--message-listening-port");
			options.add("" + messageListenerPort);
		} else {
			options.add("-f");
			options.add(propertyFilePath);
			
			if (verbose) {
				options.add("-v");
			}
		}
		
		return Arrays.copyOf(options.toArray(), options.size(), String[].class);
	}
	
	public SystemPropertyCenter(String[] args) throws URISyntaxException, ParserConfigurationException, SAXException {

		CommandLineParser parser = new BasicParser();
		Options options = new Options();
		options.addOption("h", "help", false, "Display this help message.");
		options.addOption("v", "verbose", false, "Display debug information.");
		options.addOption("a", "application", true, "Application specified to run.");
		options.addOption("f", "property-file", true, "File path of the system property file.");
		options.addOption("b", "kafka-brokers", true, "Kafka brokers' ip addresses and ports.");
		options.addOption("p", "kafka-partition", true, "Kafka brokers' number of partitions.");
		options.addOption("r", "kafka-replication-factor", true, "Kafka brokers' replication factor.");
		options.addOption("z", "zookeeper-connect", true, "Zookeeper server's ip address and port.");
		options.addOption("m", "spark-master", true, "Spark master (local[*], yarn, mesos).");
		options.addOption("d", "spart-deploy-mode", true, "Spark deploy mode (cluster, client).");
		options.addOption("n", "hdfs-default-name", true, "HDFS server ip address and port.");
		options.addOption("c", "checkpoint-dir", true, "Checkpoint directory for Spark.");
		options.addOption("y", "yarn-rm", true, "YARN resource manager hostname.");
		options.addOption("e", "num-executor", true, "Number of executors to start (Default: 2)");
		options.addOption(null, "executor-mem", true, "Memory per executor (e.g. 1000M, 2G) (Default: 1G)");
		options.addOption(null, "executor-cores", true, "Number of cores per executor (Default: 1)");
		options.addOption(null, "driver-mem", true, "Memory for driver (e.g. 1000M, 2G) (Default: 1024 Mb)");
		options.addOption(null, "driver-cores", true, "Number of cores used by the driver (Default: 1).");
		options.addOption("q", "hadoop-queue", true, "The hadoop queue to use for allocation requests (Default: 'default')");
		options.addOption(null, "kafka-fetch-message-max-bytes", true, "");
		options.addOption(null, "spark-scheduler-mode", true, "");
		options.addOption(null, "spark-shuffle-service-enabled", true, "");
		options.addOption(null, "spark-dynamicAllocation-enabled", true, "");
		options.addOption(null, "spark-dynamicAllocation-minExecutors", true, "");
		options.addOption(null, "spark-dynamicAllocation-maxExecutors", true, "");
		options.addOption(null, "spark-streaming-dynamicAllocation-enabled", true, "");
		options.addOption(null, "spark-streaming-dynamicAllocation-minExecutors", true, "");
		options.addOption(null, "spark-streaming-dynamicAllocation-maxExecutors", true, "");
		options.addOption(null, "spark-streaming-dynamicAllocation-debug", true, "");
		options.addOption(null, "spark-streaming-dynamicAllocation-delay-rounds", true, "");
		options.addOption(null, "message-listening-addr", true, "");
		options.addOption(null, "message-listening-port", true, "");
		CommandLine commandLine;
		
		try {
			commandLine = parser.parse(options, args);
		} catch (ParseException e) {
			e.printStackTrace();
			System.out.println("Try using '-h' for more information.");
		    System.exit(0);
		    return;
		}

		if (commandLine.hasOption('h')) {
			//TODO Correct help message here.
			System.out.println("Help message under development...");
		    System.exit(0);
		    return;
		}
		if (commandLine.hasOption('v')) {
			System.out.println("Verbosity enabled!");
			verbose = true;
		}
		if (commandLine.hasOption('n')) {
			hdfsDefaultName = commandLine.getOptionValue("n");
			if (verbose) {
				System.out.println("HDFS default name: " + hdfsDefaultName);
			}
		}
		if (commandLine.hasOption('a')) {
			appName = commandLine.getOptionValue('a');
			if (verbose) {
				System.out.println("To start application " + appName + "...");
			}
		}
		if (commandLine.hasOption('m')) {
			sparkMaster = commandLine.getOptionValue('m');
			//In case user specify deploy mode using "yarn-client" or "yarn-cluster".
			if (sparkMaster.contains("client")) {
				sparkDeployMode = "client";
			} else if (sparkMaster.contains("cluster")) {
				sparkDeployMode = "cluster";
			}
			
			if (sparkMaster.contains("yarn") && !onYARN) {
				onYARN = true;
				if (verbose) {
					System.out.println("To run on YARN...");
				}
			}
		}
		if (commandLine.hasOption('f')) {
			propertyFilePath = commandLine.getOptionValue('f');
			if (!propertyFilePath.equals("")) {
				//Load the property file.
				Properties systemProperties = new Properties();
				BufferedInputStream propInputStream;
				try {
					if (onYARN || propertyFilePath.contains("file:/") || propertyFilePath.contains("hdfs:/")) {
						if (verbose) {
							System.out.println("Loading properties using HDFS platform from " + propertyFilePath + "...");
						}
						
						FileSystem fileSystem = FileSystem.get(new URI(propertyFilePath), HadoopUtils.getDefaultConf());
						FSDataInputStream hdfsInputStream = fileSystem.open(new Path(propertyFilePath)); 
						propInputStream = new BufferedInputStream(hdfsInputStream);
					} else {
						if (verbose) {
							System.out.println("Loading properties locally from " + propertyFilePath + "...");
						}
						
						propInputStream = new BufferedInputStream(new FileInputStream(propertyFilePath));
					}
					systemProperties.load(propInputStream);
				} catch (IOException e) {
					e.printStackTrace();
					System.err.printf("Cannot load system property file at specified path: \"%s\"!\n", propertyFilePath);
					System.out.println("Try use '-h' for more information.");
				    System.exit(0);
				    return;
				}
				
				//Digest the settings.
				for (Entry<Object, Object> entry : systemProperties.entrySet()) {
					if (verbose) {
						System.out.println("Read from property file: " + entry.getKey() + "=" + entry.getValue());
					}
					switch ((String) entry.getKey()) {
					case "zookeeper.connect":
						zookeeperConnect = (String) entry.getValue(); 
						break;
					case "kafka.brokers":
						kafkaBrokers = (String) entry.getValue(); 
						break;
					case "kafka.partitions":
						kafkaPartitions = new Integer((String) entry.getValue()); 
						break;
					case "kafka.replication.factor":
						kafkaReplicationFactor = new Integer((String) entry.getValue()); 
						break;
					case "kafka.fetch.message.max.bytes":
						kafkaFetchMessageMaxBytes = new Integer((String) entry.getValue());
						break;
					case "checkpoint.directory":
						checkpointDir = (String) entry.getValue(); 
						break;
					case "spark.master":
						sparkMaster = (String) entry.getValue(); 
						break;
					case "spark.deploy.mode":
						sparkDeployMode = (String) entry.getValue();
						break;
					case "vpe.platform.jar":
						jarPath = (String) entry.getValue();
						break;
					case "yarn.resource.manager.hostname":
						yarnResourceManagerHostname = (String) entry.getValue();
						break;
					case "hdfs.default.name":
						hdfsDefaultName = (String) entry.getValue();
						break;
					case "executor.num":
						numExecutors = new Integer((String) entry.getValue()); 
						break;
					case "executor.memory":
						executorMem = (String) entry.getValue();
						break;
					case "executor.cores":
						executorCores = new Integer((String) entry.getValue()); 
						break;
					case "driver.memory":
						driverMem = (String) entry.getValue();
						break;
					case "driver.cores":
						driverCores = new Integer((String) entry.getValue()); 
						break;
					case "hadoop.queue":
						hadoopQueue = (String) entry.getValue(); 
						break;
					case "spark.scheduler.mode":
						sparkSchedulerMode = (String) entry.getValue(); 
						break;
					case "spark.shuffle.service.enabled":
						sparkShuffleServiceEnabled = (String) entry.getValue(); 
						break;
					case "spark.dynamicAllocation.enabled":
						sparkDynamicAllocationEnabled = (String) entry.getValue(); 
						break;
					case "spark.dynamicAllocation.minExecutors":
						sparkDynamicAllocationMinExecutors = (String) entry.getValue(); 
						break;
					case "spark.dynamicAllocation.maxExecutors":
						sparkDynamicAllocationMaxExecutors = (String) entry.getValue(); 
						break;
					case "spark.streaming.dynamicAllocation.enabled":
						sparkStreamingDynamicAllocationEnabled = (String) entry.getValue(); 
						break;
					case "spark.streaming.dynamicAllocation.minExecutors":
						sparkStreamingDynamicAllocationMinExecutors = (String) entry.getValue(); 
						break;
					case "spark.streaming.dynamicAllocation.maxExecutors":
						sparkStreamingDynamicAllocationMaxExecutors = (String) entry.getValue(); 
						break;
					case "spark.streaming.dynamicAllocation.debug":
						sparkStreamingDynamicAllocationDebug = (String) entry.getValue(); 
						break;
					case "spark.streaming.dynamicAllocation.delay.rounds":
						sparkStreamingDynamicAllocationDelayRounds = (String) entry.getValue(); 
						break;
					}
				}
			}
		}
		if (commandLine.hasOption('b')) {
			kafkaBrokers = commandLine.getOptionValue('b');
		}
		if (commandLine.hasOption('p')) {
			kafkaPartitions = new Integer(commandLine.getOptionValue('p'));
		}
		if (commandLine.hasOption('r')) {
			kafkaReplicationFactor = new Integer(commandLine.getOptionValue('r'));
		}
		if (commandLine.hasOption("kafka-fetch-message-max-bytes")) {
			kafkaFetchMessageMaxBytes = new Integer(commandLine.getOptionValue("kafka-fetch-message-max-bytes"));
		}
		if (commandLine.hasOption('z')) {
			zookeeperConnect = commandLine.getOptionValue('z');
		}
		if (commandLine.hasOption('d')) {
			sparkDeployMode = commandLine.getOptionValue("d");
		}
		if (commandLine.hasOption('c')) {
			checkpointDir = commandLine.getOptionValue("c");
			System.out.println("Checkpoint directory set to \"" + checkpointDir + "\"");
		}
		if (commandLine.hasOption('y')) {
			yarnResourceManagerHostname = commandLine.getOptionValue("y");
		}
		if (commandLine.hasOption('e')) {
			numExecutors = new Integer(commandLine.getOptionValue('e'));
		}
		if (commandLine.hasOption("executor-mem")) {
			executorMem = commandLine.getOptionValue("executor-mem");
		}
		if (commandLine.hasOption("executor-cores")) {
			executorCores = new Integer(commandLine.getOptionValue("executor-cores"));
		}
		if (commandLine.hasOption("driver-mem")) {
			driverMem = commandLine.getOptionValue("driver-mem");
		}
		if (commandLine.hasOption("driver-cores")) {
			driverCores = new Integer(commandLine.getOptionValue("driver-cores"));
		}
		if (commandLine.hasOption("q")) {
			hadoopQueue = commandLine.getOptionValue("q");
		}
		if (commandLine.hasOption("spark-scheduler-mode")) {
			sparkSchedulerMode = commandLine.getOptionValue("spark-scheduler-mode");
		}
		if (commandLine.hasOption("spark-shuffle-service-enabled")) {
			sparkShuffleServiceEnabled = commandLine.getOptionValue("spark-shuffle-service-enabled");
		}
		if (commandLine.hasOption("spark-dynamicAllocation-enabled")) {
			sparkDynamicAllocationEnabled = commandLine.getOptionValue("spark-dynamicAllocation-enabled");
		}
		if (commandLine.hasOption("spark-dynamicAllocation-minExecutors")) {
			sparkDynamicAllocationMinExecutors =
					commandLine.getOptionValue("spark-dynamicAllocation-minExecutors");
		}
		if (commandLine.hasOption("spark-dynamicAllocation-maxExecutors")) {
			sparkDynamicAllocationMaxExecutors =
					commandLine.getOptionValue("spark-dynamicAllocation-maxExecutors");
		}
		if (commandLine.hasOption("spark-streaming-dynamicAllocation-enabled")) {
			sparkStreamingDynamicAllocationEnabled =
					commandLine.getOptionValue("spark-streaming-dynamicAllocation-enabled");
		}
		if (commandLine.hasOption("spark-streaming-dynamicAllocation-minExecutors")) {
			sparkStreamingDynamicAllocationMinExecutors =
					commandLine.getOptionValue("spark-streaming-dynamicAllocation-minExecutors");
		}
		if (commandLine.hasOption("spark-streaming-dynamicAllocation-maxExecutors")) {
			sparkStreamingDynamicAllocationMaxExecutors =
					commandLine.getOptionValue("spark-streaming-dynamicAllocation-maxExecutors");
		}
		if (commandLine.hasOption("spark-streaming-dynamicAllocation-debug")) {
			sparkStreamingDynamicAllocationDebug =
					commandLine.getOptionValue("spark-streaming-dynamicAllocation-debug");
		}
		if (commandLine.hasOption("spark-streaming-dynamicAllocation-delay-rounds")) {
			sparkStreamingDynamicAllocationDelayRounds =
					commandLine.getOptionValue("spark-streaming-dynamicAllocation-delay-rounds");
		}
		if (commandLine.hasOption("message-listening-addr")) {
			messageListenerAddress =
					commandLine.getOptionValue("message-listening-addr");
		}
		if (commandLine.hasOption("message-listening-port")) {
			messageListenerPort =
					new Integer(commandLine.getOptionValue("message-listening-port"));
		}
		
		if (sparkMaster.contains("yarn") && !onYARN) {
			onYARN = true;
			if (verbose) {
				System.out.println("To run on YARN...");
			}
		}
	}
}
