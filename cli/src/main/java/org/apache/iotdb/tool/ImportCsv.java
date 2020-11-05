/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.tool;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import jline.console.ConsoleReader;
import me.tongfei.progressbar.ProgressBar;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.iotdb.exception.ArgsErrorException;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.session.Session;
import org.apache.iotdb.tsfile.common.constant.TsFileConstant;

/**
 * read a CSV formatted data File and insert all the data into IoTDB.
 */
public class ImportCsv extends AbstractCsvTool {
  private static final String FILE_ARGS = "f";
  private static final String FILE_NAME = "file or folder";
  private static final String FILE_SUFFIX = "csv";

  private static final String TSFILEDB_CLI_PREFIX = "ImportCsv";
  private static final String ILLEGAL_PATH_ARGUMENT = "Path parameter is null";

  /**
   * create the commandline options.
   *
   * @return object Options
   */
  private static Options createOptions() {
    Options options = new Options();

    Option opHost = Option.builder(HOST_ARGS).longOpt(HOST_NAME).required()
        .argName(HOST_NAME).hasArg().desc("Host Name (required)").build();
    options.addOption(opHost);

    Option opPort = Option.builder(PORT_ARGS).longOpt(PORT_NAME).required()
        .argName(PORT_NAME).hasArg().desc("Port (required)").build();
    options.addOption(opPort);

    Option opUsername = Option.builder(USERNAME_ARGS).longOpt(USERNAME_NAME)
        .required().argName(USERNAME_NAME)
        .hasArg().desc("Username (required)").build();
    options.addOption(opUsername);

    Option opPassword = Option.builder(PASSWORD_ARGS).longOpt(PASSWORD_NAME)
        .optionalArg(true).argName(PASSWORD_NAME).hasArg().desc("Password (optional)").build();
    options.addOption(opPassword);

    Option opFile = Option.builder(FILE_ARGS).required().argName(FILE_NAME).hasArg().desc(
        "If input a file path, load a csv file, "
            + "otherwise load all csv file under this directory (required)")
        .build();
    options.addOption(opFile);

    Option opHelp = Option.builder(HELP_ARGS).longOpt(HELP_ARGS)
        .hasArg(false).desc("Display help information")
        .build();
    options.addOption(opHelp);

    Option opTimeZone = Option.builder(TIME_ZONE_ARGS).argName(TIME_ZONE_NAME).hasArg()
        .desc("Time Zone eg. +08:00 or -01:00 (optional)").build();
    options.addOption(opTimeZone);

    return options;
  }

  /**
   * Data from csv To tsfile.
   */
  private static void loadDataFromCSV(File file) {
    int fileLine;
    try {
      fileLine = getFileLineCount(file);
    } catch (IOException e) {
      System.out.println("Failed to import file: " + file.getName());
      return;
    }
    System.out.println("Start to import data from: " + file.getName());
    try(BufferedReader br = new BufferedReader(new FileReader(file));
        ProgressBar pb = new ProgressBar("Import from: " + file.getName(), fileLine)) {
      pb.setExtraMessage("Importing...");
      String header = br.readLine();
      String[] cols = splitCsvLine(header);
      if (cols.length <= 1) {
        System.out.println("The CSV file "+ file.getName() +" illegal, please check first line");
        return;
      }

      List<String> devices = new ArrayList<>();
      List<Long> times = new ArrayList<>();
      List<List<String>> measurementsList = new ArrayList<>();
      List<List<String>> valuesList = new ArrayList<>();
      Map<String, Map<String, Integer>> devicesToMeasurementsAndPositions = new HashMap<>();

      for(int i = 1; i < cols.length; i++) {
        splitColToDeviceAndMeasurement(cols[i], devicesToMeasurementsAndPositions, i);
      }

      String line;
      while((line = br.readLine()) != null) {
        cols = splitCsvLine(line);
        for(Entry<String, Map<String, Integer>> deviceToMeasurementsAndPositions: devicesToMeasurementsAndPositions.entrySet()) {
          devices.add(deviceToMeasurementsAndPositions.getKey());
          times.add(Long.parseLong(cols[0]));
          Map<String, Integer> measurementsAndPositions = deviceToMeasurementsAndPositions.getValue();
          List<String> measurements = new ArrayList<>();
          List<String> values = new ArrayList<>();
          for(Entry<String, Integer> measurementAndPosition : measurementsAndPositions.entrySet()) {
            measurements.add(measurementAndPosition.getKey());
            if(cols[measurementAndPosition.getValue()].equals("") && cols[measurementAndPosition.getValue()].equals("null")) {
              values.add(null);
            } else {
              values.add(cols[measurementAndPosition.getValue()]);
            }
          }
          measurementsList.add(measurements);
          valuesList.add(values);
        }
      }
      session.insertRecords(devices, times, measurementsList, valuesList);
      System.out.println("Insert csv successfully!");
      pb.stepTo(fileLine);
    } catch (FileNotFoundException e) {
      System.out.println("Cannot find " + file.getName() + " because: "+e.getMessage());
    } catch (IOException e) {
      System.out.println("CSV file read exception because: " + e.getMessage());
    } catch (IoTDBConnectionException | StatementExecutionException e) {
      System.out.println("Meet error when insert csv because " + e.getMessage());
    } finally {
      try {
        if (session != null) {
          session.close();
        }
      } catch (IoTDBConnectionException e) {
        System.out.println("Sql statement can not be closed because: " + e.getMessage());
      }
    }
  }


  public static void main(String[] args) throws IOException {
    Options options = createOptions();
    HelpFormatter hf = new HelpFormatter();
    hf.setOptionComparator(null);
    hf.setWidth(MAX_HELP_CONSOLE_WIDTH);
    CommandLine commandLine;
    CommandLineParser parser = new DefaultParser();

    if (args == null || args.length == 0) {
      System.out.println("Too few params input, please check the following hint.");
      hf.printHelp(TSFILEDB_CLI_PREFIX, options, true);
      return;
    }
    try {
      commandLine = parser.parse(options, args);
    } catch (ParseException e) {
      System.out.println("Parse error: " + e.getMessage());
      hf.printHelp(TSFILEDB_CLI_PREFIX, options, true);
      return;
    }
    if (commandLine.hasOption(HELP_ARGS)) {
      hf.printHelp(TSFILEDB_CLI_PREFIX, options, true);
      return;
    }

    ConsoleReader reader = new ConsoleReader();
    reader.setExpandEvents(false);
    try {
      parseBasicParams(commandLine, reader);
      String filename = commandLine.getOptionValue(FILE_ARGS);
      if (filename == null) {
        hf.printHelp(TSFILEDB_CLI_PREFIX, options, true);
        return;
      }
      parseSpecialParams(commandLine);
      importCsvFromFile(host, port, username, password, filename, timeZoneID);
    } catch (ArgsErrorException e) {
      System.out.println("Args error: " + e.getMessage());
    } catch (Exception e) {
      System.out.println("Encounter an error, because: " + e.getMessage());
    } finally {
      reader.close();
    }
  }

  private static void parseSpecialParams(CommandLine commandLine) {
    timeZoneID = commandLine.getOptionValue(TIME_ZONE_ARGS);
  }

  public static void importCsvFromFile(String ip, String port, String username,
      String password, String filename,
      String timeZone){
    try {
      session = new Session(ip, Integer.parseInt(port), username, password);
      session.open(false);
      timeZoneID = timeZone;
      setTimeZone();

      File file = new File(filename);
      if (file.isFile()) {
        importFromSingleFile(file);
      } else if (file.isDirectory()) {
        importFromDirectory(file);
      }
    } catch (IoTDBConnectionException e) {
      System.out.println("Encounter an error when connecting to server, because " + e.getMessage());
    } catch (StatementExecutionException e) {
      System.out.println("Encounter an error when executing the statement, because " + e.getMessage());
    } finally {
      if (session != null) {
        try {
          session.close();
        } catch (IoTDBConnectionException e) {
          System.out.println("Encounter an error when closing the connection, because " + e.getMessage());
        }
      }
    }
  }

  private static void importFromSingleFile(File file) {
    if (file.getName().endsWith(FILE_SUFFIX)) {
      loadDataFromCSV(file);
    } else {
      System.out.println("File "+ file.getName() +"  should ends with '.csv' if you want to import");
    }
  }

  private static void importFromDirectory(File file) {
    File[] files = file.listFiles();
    if (files == null) {
      return;
    }

    for (File subFile : files) {
      if (subFile.isFile()) {
        if (subFile.getName().endsWith(FILE_SUFFIX)) {
          loadDataFromCSV(subFile);
        } else {
          System.out.println("File " + file.getName() + " should ends with '.csv' if you want to import");
        }
      }
    }
  }

  private static int getFileLineCount(File file) throws IOException {
    int line;
    try (LineNumberReader count = new LineNumberReader(new FileReader(file))) {
      while (count.skip(Long.MAX_VALUE) > 0) {
        // Loop just in case the file is > Long.MAX_VALUE or skip() decides to not read the entire file
      }
      // +1 because line index starts at 0
      line = count.getLineNumber() + 1;
    }
    return line;
  }

  private static void splitColToDeviceAndMeasurement(String col, Map<String, Map<String, Integer>> deviceToMeasurementsAndPositions, int position) {
    if (col.length() > 0) {
      if (col.charAt(col.length() - 1) == TsFileConstant.DOUBLE_QUOTE) {
        int endIndex = col.lastIndexOf('"', col.length() - 2);
        // if a double quotes with escape character
        while (endIndex != -1 && col.charAt(endIndex - 1) == '\\') {
          endIndex = col.lastIndexOf('"', endIndex - 2);
        }
        if (endIndex != -1 && (endIndex == 0 || col.charAt(endIndex - 1) == '.')) {
          putDeviceAndMeasurement(col.substring(0, endIndex - 1), col.substring(endIndex), deviceToMeasurementsAndPositions, position);
        } else {
          throw new IllegalArgumentException(ILLEGAL_PATH_ARGUMENT);
        }
      } else if (col.charAt(col.length() - 1) != TsFileConstant.DOUBLE_QUOTE
          && col.charAt(col.length() - 1) != TsFileConstant.PATH_SEPARATOR_CHAR) {
        int endIndex = col.lastIndexOf(TsFileConstant.PATH_SEPARATOR_CHAR);
        if (endIndex < 0) {
          putDeviceAndMeasurement("", col, deviceToMeasurementsAndPositions, position);
        } else {
          putDeviceAndMeasurement(col.substring(0, endIndex), col.substring(endIndex + 1), deviceToMeasurementsAndPositions, position);
        }
      } else {
        throw new IllegalArgumentException(ILLEGAL_PATH_ARGUMENT);
      }
    } else {
      putDeviceAndMeasurement("", col, deviceToMeasurementsAndPositions, position);
    }
  }

  private static void putDeviceAndMeasurement(String device, String measurement, Map<String, Map<String, Integer>> deviceToMeasurementsAndPositions, int position) {
    if(deviceToMeasurementsAndPositions.get(device) == null) {
      Map<String, Integer> measurementsAndPositions = new HashMap<>();
      measurementsAndPositions.put(measurement, position);
      deviceToMeasurementsAndPositions.put(device, measurementsAndPositions);
    } else {
      deviceToMeasurementsAndPositions.get(device).put(measurement, position);
    }
  }

  public static String[] splitCsvLine(String path) {
    List<String> nodes = new ArrayList<>();
    int startIndex = 0;
    for (int i = 0; i < path.length(); i++) {
      if (path.charAt(i) == ',') {
        nodes.add(path.substring(startIndex, i));
        startIndex = i + 1;
      } else if (path.charAt(i) == '"') {
        int endIndex = path.indexOf('"', i + 1);
        // if a double quotes with escape character
        while (endIndex != -1 && path.charAt(endIndex - 1) == '\\') {
          endIndex = path.indexOf('"', endIndex + 1);
        }
        if (endIndex != -1 && (endIndex == path.length() - 1 || path.charAt(endIndex + 1) == ',')) {
          nodes.add(path.substring(startIndex + 1, endIndex));
          i = endIndex + 1;
          startIndex = endIndex + 2;
        } else {
          throw new IllegalArgumentException("Illegal csv line: " + path);
        }
      } else if (path.charAt(i) == '\'') {
        int endIndex = path.indexOf('\'', i + 1);
        // if a double quotes with escape character
        while (endIndex != -1 && path.charAt(endIndex - 1) == '\\') {
          endIndex = path.indexOf('\'', endIndex + 1);
        }
        if (endIndex != -1 && (endIndex == path.length() - 1 || path.charAt(endIndex + 1) == ',')) {
          nodes.add(path.substring(startIndex + 1, endIndex));
          i = endIndex + 1;
          startIndex = endIndex + 2;
        } else {
          throw new IllegalArgumentException("Illegal csv line: " + path);
        }
      }
    }
    if (startIndex <= path.length() - 1) {
      nodes.add(path.substring(startIndex));
    }
    return nodes.toArray(new String[0]);
  }
}
