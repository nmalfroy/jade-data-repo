package common;

import collector.MeasurementCollector;
import collector.config.MeasurementList;
import common.utils.FileUtils;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import runner.TestRunner;
import runner.config.TestConfiguration;
import runner.config.TestSuite;
import uploader.ResultUploader;

public class CommandCLI {
  public static final String ANSI_RESET = "\u001B[0m";
  public static final String ANSI_PURPLE = "\u001B[35m";

  public static void printHelp() throws IOException {
    // execute a test configuration or suite
    System.out.println(
        ANSI_PURPLE
            + "Usage (1): ./gradlew runTest --args=\"configOrSuiteFileName outputDirectoryName\""
            + ANSI_RESET
            + System.lineSeparator()
            + "  configOrSuiteFileName = file name of the test configuration or suite JSON file"
            + System.lineSeparator()
            + "  outputDirectoryName = name of the directory where the results will be written"
            + System.lineSeparator());
    // print out the available test configurations and suites found in the resources directory
    System.out.println("  The following test configuration files were found:");
    printAvailableFiles(TestConfiguration.resourceDirectory, null);
    System.out.println("  The following test suite files were found:");
    printAvailableFiles(TestSuite.resourceDirectory, null);

    // collect measurements for a test run
    System.out.println(
        ANSI_PURPLE
            + "Usage (2): ./gradlew collectMeasurements --args=\"measurementListFileName outputDirectoryName\""
            + ANSI_RESET
            + System.lineSeparator()
            + "  measurementListFileName = file name of the measurement list JSON file"
            + System.lineSeparator()
            + "  outputDirectoryName = name of the same directory that contains the Test Runner results"
            + System.lineSeparator());

    // collect measurements for a time interval
    System.out.println(
        ANSI_PURPLE
            + "Usage (3): ./gradlew collectMeasurements --args=\"measurementListFileName outputDirectoryName serverFileName startTimestamp endTimestamp\""
            + ANSI_RESET
            + System.lineSeparator()
            + "  measurementListFileName = file name of the measurement list JSON file"
            + System.lineSeparator()
            + "  outputDirectoryName = name of the directory where the results will be written"
            + System.lineSeparator()
            + "  serverFileName = name of the server JSON file"
            + System.lineSeparator()
            + "  startTimestamp = start of the interval; format must be yyyy-mm-dd hh:mm:ss[.fffffffff] (UTC timezone)"
            + System.lineSeparator()
            + "  endTimestamp = end of the interval; format must be yyyy-mm-dd hh:mm:ss[.fffffffff] (UTC timezone)"
            + System.lineSeparator());
    // print out the available measurement lists found in the resources directory
    System.out.println("  The following measurement lists were found:");
    printAvailableFiles(MeasurementList.resourceDirectory, null);

    // upload results and measurements for a test run
    System.out.println(
        ANSI_PURPLE
            + "Usage (4): ./gradlew uploadResults --args=\"uploadListFileName outputDirectoryName\""
            + ANSI_RESET
            + System.lineSeparator()
            + "  uploadListFileName = file name of the upload list JSON file"
            + System.lineSeparator()
            + "  outputDirectoryName = name of the same directory that contains the Test Runner and measurement results"
            + System.lineSeparator());

    // example workflows
    System.out.println(
        ANSI_PURPLE
            + "Example Workflow (5): Execute a test configuration, collect the measurements generated by the server during the run, and upload the results"
            + ANSI_RESET
            + System.lineSeparator()
            + "  ./gradlew runTest --args=\"configs/basicexamples/BasicUnauthenticated.json /tmp/TestRunnerResults\""
            + System.lineSeparator()
            + "  ./gradlew collectMeasurements --args=\"BasicKubernetes.json /tmp/TestRunnerResults\""
            + System.lineSeparator()
            + "  ./gradlew uploadResults --args=\"BroadJadeDev.json /tmp/TestRunnerResults\""
            + System.lineSeparator());
    System.out.println(
        ANSI_PURPLE
            + "Example Workflow (6): Collect the measurements generated by the server for a particular time interval"
            + ANSI_RESET
            + System.lineSeparator()
            + "  ./gradlew collectMeasurements --args=\"AllMeasurements.json /tmp/TestRunnerResults mmdev.json '2020-08-20 13:18:34' '2020-08-20 13:18:35.615628881'\""
            + System.lineSeparator());
    System.out.println(
        ANSI_PURPLE
            + "Example Workflow (7): Execute a test suite"
            + ANSI_RESET
            + System.lineSeparator()
            + "  ./gradlew runTest --args=\"suites/BasicSmoke.json /tmp/TestRunnerResults\""
            + System.lineSeparator());
  }

  public static void runTestMain(String[] args) throws Exception {
    if (args.length == 2) { // execute a test configuration or suite
      boolean isFailure = TestRunner.runTest(args[0], args[1]);
      if (isFailure) {
        System.exit(1);
      }
    } else { // if no args specified or invalid number of args specified, print help
      printHelp();
    }
  }

  public static void collectMeasurementsMain(String[] args) throws Exception {
    if (args.length == 2) { // collect measurements for a test run
      String measurementListFileName = args[0];
      String outputDirName = args[1];
      MeasurementCollector.collectMeasurementsForTestRun(measurementListFileName, outputDirName);
    } else if (args.length == 5) { // collect measurements for a time interval
      String measurementListFileName = args[0];
      String outputDirName = args[1];
      String serverFileName = args[2];
      String startTimestamp = args[3];
      String endTimestamp = args[4];
      MeasurementCollector.collectMeasurements(
          measurementListFileName, outputDirName, serverFileName, startTimestamp, endTimestamp);
    } else { // if no args specified or invalid number of args specified, print help
      printHelp();
    }
  }

  public static void uploadResultsMain(String[] args) throws Exception {
    if (args.length == 2) { // upload results
      String uploadListFileName = args[0];
      String outputDirName = args[1];
      ResultUploader.uploadResults(uploadListFileName, outputDirName);
    } else { // if no args specified or invalid number of args specified, print help
      printHelp();
    }
  }

  public static void printAvailableFiles(String subDirectoryName, Path parentDirectory)
      throws IOException {
    // use the resources directory as the default parent directory
    File parentDirectoryFile;
    if (parentDirectory == null) {
      parentDirectoryFile =
          new File(CommandCLI.class.getClassLoader().getResource(subDirectoryName).getFile());
    } else {
      parentDirectoryFile = parentDirectory.resolve(subDirectoryName).toFile();
    }

    List<String> availableTestConfigs = FileUtils.getFilesInDirectory(parentDirectoryFile);
    for (String testConfigFilePath : availableTestConfigs) {
      System.out.println("    " + testConfigFilePath);
    }
    System.out.println();
  }
}
