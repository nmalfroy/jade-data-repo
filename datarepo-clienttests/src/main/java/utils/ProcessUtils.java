package utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

public final class ProcessUtils {

  private ProcessUtils() {}

  /**
   * Executes a command in a separate process.
   *
   * @param cmdArgs a list of the command line arguments
   * @return a List of the lines written to stdout
   * @throws IOException
   */
  public static List<String> executeCommand(String cmd, List<String> cmdArgs) throws IOException {
    // build and run process
    cmdArgs.add(0, cmd);
    ProcessBuilder procBuilder = new ProcessBuilder(cmdArgs);
    Process proc = procBuilder.start();

    // read in all lines written to stdout
    BufferedReader bufferedReader =
        new BufferedReader(new InputStreamReader(proc.getInputStream(), Charset.defaultCharset()));
    String outputLine;
    List<String> outputLines = new ArrayList<>();
    while ((outputLine = bufferedReader.readLine()) != null) {
      outputLines.add(outputLine);
    }
    bufferedReader.close();

    return outputLines;
  }
}