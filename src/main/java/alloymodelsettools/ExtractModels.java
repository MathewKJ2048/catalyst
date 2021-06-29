/*
 * Catalyst -- A framework for performance analysis/optimization of Alloy models
 * Copyright (C) 2018-2019 Amin Bandali
 *
 * This file is part of Catalyst.
 *
 * Catalyst is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Catalyst is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Catalyst.  If not, see <https://www.gnu.org/licenses/>.
 */

package alloymodelsettools;

import java.io.*;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.ast.CommandScope;
import edu.mit.csail.sdg.ast.Module;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;


// Extract a list of sat/unsat models, also find appropriate scopes which let solving time fall in a desired range.
public class ExtractModels {
    // Users set these options.
    // Change this to resume an interrupted process
    static int file_count = 0;
    // Path to existing model set directory to extract models in, path can either be relative or absolute,  relative
    // path are expected to be relative to "alloy-model-sets/". One example: "model-sets/2021-05-07-14-22-48".
    static String dirname = "model-sets/placeholder";
    static long lower_bound_of_time_range_in_seconds = 2 * 60;
    static long higher_bound_of_time_range_in_seconds = 10 * 60;
    // min scope to stop looking for scope
    static int min_scope = 10;
    // max scope to stop looking for scope
    static int max_scope = 300;
    static int num_sat_wanted = 200;
    static int num_unsat_wanted = 200;
    // You don't need to change anything after this line

    // static variables
    static Random randomGenerator = new Random();
    static String file_of_files = dirname + "/random-files-list.txt";
    static String file_sat_list = dirname + "/sat_models.txt";
    static String file_unsat_list = dirname + "/unsat_models.txt";
    static FileWriter readmefile;
    static FileWriter satfile;
    static FileWriter unsatfile;
    static CSVPrinter summaryfile;
    static Logger logger;
    static CSVPrinter csvPrinter;
    static Result lastResult;
    static List<String> file_names = new ArrayList<String>();
    static int num_sat = 0;
    static int num_unsat = 0;
    // stdio is used for error output

    static Integer Setup() {
        try {
            String readmefilename = dirname + "/" + "README.md";

            File f = new File(readmefilename);
            f.getParentFile().mkdirs();
            readmefile = new FileWriter(readmefilename, true);

            // Set up logger
            logger = Logger.getLogger("MyLog");
            FileHandler fh;
            // Configure the logger with handler and formatter
            fh = new FileHandler(dirname + "/log.txt", true);
            logger.addHandler(fh);
            System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tc %2$s%n%4$s: %5$s%6$s%n");
            SimpleFormatter formatter = new SimpleFormatter();
            fh.setFormatter(formatter);
            // Print out $machine info
            logger.info(util.getSystemInfo());
            return 0;
        } catch (Exception e) {
            System.out.println("An error occurred when setting up the readme file and logger.");
            return 1;
        }
    }


    // https://stackoverflow.com/questions/636367/executing-a-java-application-in-a-separate-process
    public static final class JavaProcess {

        private JavaProcess() {
        }

        public static Process getJavaProcess(Class klass, List<String> args) throws IOException,
                InterruptedException {
            String javaHome = System.getProperty("java.home");
            String javaBin = javaHome +
                    File.separator + "bin" +
                    File.separator + "java";
            String classpath = System.getProperty("java.class.path");
            String className = klass.getName();

            List<String> command = new LinkedList<String>();
            command.add(javaBin);
            command.add("-cp");
            command.add(classpath);
            command.add(className);
            if (args != null) {
                command.addAll(args);
            }

            ProcessBuilder builder = new ProcessBuilder(command);
            return builder.start();
        }
    }

    // There are 5 possible outcomes of running a command in an Alloy model
    // SUCCESS: It executes successfully with execution time falls in the desired time range
    //          [lower_bound_of_time_range_in_seconds, higher_bound_of_time_range_in_seconds]
    // TOOSHORT: It executes successfully but the execution time is shorter than lower_bound_of_time_range_in_seconds
    // TIMEOUT: It times out in higher_bound_of_time_range_in_seconds seconds.
    // EXCEPTION: It throws an exception when executing the command.
    // UNKNOWN: Unknown error has occurred. It should never reach here.
    public enum Status {
        SUCCESS, TOOSHORT, TIMEOUT, EXCEPTION, UNKNOWN
    }

    public static class Result {
        public final Status status;
        public final Long time;
        public final String satisfiable;

        public Result(Status x, Long y, String z) {
            this.status = x;
            this.time = y;
            this.satisfiable = z;
        }
    }

    // Run the i-th command in the als file specified with the filePath, with
    // scope set to overallScope. If overallScope is -1, runs the original
    // command. Returns enum status as explained above.
    public static Result runCommand(String filePath, int i, int overallScope) {
        try {
            Process process = JavaProcess.getJavaProcess(RunCommand.class,
                    Arrays.asList(filePath, String.valueOf(i), String.valueOf(overallScope)));
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            long executionTime = higher_bound_of_time_range_in_seconds * 1000000000;
            String satisfiable = "";
            boolean outOfMemoryError = false;
            while ((line = reader.readLine()) != null) {
                if (line.contains("Execution time(ns)")) {
                    executionTime = Long.parseLong(line.split(": ")[1]);
                } else if (line.contains("Satisfiable?")) {
                    satisfiable = line.split(": ")[1];
                } else if (line.contains("java.lang.OutOfMemoryError") || line.contains("Translation capacity exceeded.")) {
                    outOfMemoryError = true;
                }
                logger.info(line);
            }
            int returnValue = process.waitFor();
            if (returnValue == 0) {
                logger.info("Java Process: Alright!");
                if (executionTime > higher_bound_of_time_range_in_seconds * 1000000000) {
                    return new Result(Status.TIMEOUT, (long) -1, "");
                } else if (executionTime >= lower_bound_of_time_range_in_seconds * 1000000000) {
                    return new Result(Status.SUCCESS, executionTime, satisfiable);
                } else {
                    logger.info("Takes too short!");
                    return new Result(Status.TOOSHORT, executionTime, satisfiable);
                }
            } else if (returnValue == 1) {
                logger.info("Java Process: Timeout!");
                logger.info("Takes too long!");
                return new Result(Status.TIMEOUT, (long) -1, "");
            } else if (returnValue == 2) {
                logger.info("Java Process: Exception thrown!");
                if (outOfMemoryError) {
                    logger.info("Out of memory error, treated as timeout");
                    return new Result(Status.TIMEOUT, (long) -1, "");
                } else {
                    logger.warning("Attention! Other exceptions (not oom) are thrown.");
                    return new Result(Status.EXCEPTION, (long) -1, "");
                }
            } else {
                logger.warning("Java Process: Unknown state!");
                return new Result(Status.UNKNOWN, (long) -1, "");
            }
        } catch (Exception e) {
            logger.warning("Java Process: Unknown state!");
            logger.log(Level.SEVERE, e.getMessage(), e);
            return new Result(Status.UNKNOWN, (long) -1, "");
        }
    }

    // Returns one scope in range [min_scope, max_scope] whose runtime of the
    // i-th command in that als file falls in the desired time range.
    // -1 if we cannot find anything
    static Integer binarySearch(String als_file_path, int which_command, Command cmd, int min_scope, int max_scope) {
        if (max_scope >= min_scope) {
            int mid_scope = min_scope + (max_scope - min_scope) / 2;
            lastResult = runCommand(als_file_path, which_command, mid_scope);
            try {
                if (num_sat >= num_sat_wanted && lastResult.satisfiable.equals("SAT")) {
                    logger.info("Enough sat models");
                    csvFailureRecord(als_file_path, which_command, cmd, "Enough sat models");
                    return -1;
                } else if (num_unsat >= num_unsat_wanted && lastResult.satisfiable.equals("UNSAT")) {
                    logger.info("Enough unsat models");
                    csvFailureRecord(als_file_path, which_command, cmd, "Enough unsat models");
                    return -1;
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                return -1;
            }
            Status exitStatus = lastResult.status;
            // If mid_scope is what we want
            if (exitStatus == Status.SUCCESS)
                return mid_scope;

            // If mid_scope is taking too short, then it can only be present
            // in smaller scopes
            if (exitStatus == Status.TIMEOUT)
                return binarySearch(als_file_path, which_command, cmd, min_scope, mid_scope - 1);

            if (exitStatus == Status.EXCEPTION || exitStatus == Status.UNKNOWN) {
                logger.warning("Exception or unknown error thrown when doing binary search with scope " + mid_scope);
                try {
                    csvFailureRecord(als_file_path, which_command, cmd, "Other exceptions or unknown state");
                } catch (Exception e) {
                    logger.log(Level.SEVERE, e.getMessage(), e);
                    return -1;
                }
                return -1;
            }

            // Else we will search for larger scopes
            return binarySearch(als_file_path, which_command, cmd, mid_scope + 1, max_scope);
        }

        try {
            // We reach here when no scope in the range have desired execution time
            if (max_scope < ExtractModels.min_scope) {
                logger.info("Scope not found after searching for " + min_scope);
                csvFailureRecord(als_file_path, which_command, cmd, "Scope not found above " + min_scope);
                return -1;
            } else if (min_scope > ExtractModels.max_scope) {
                logger.info("Scope not found after searching for " + max_scope);
                csvFailureRecord(als_file_path, which_command, cmd, "Scope not found under " + max_scope);
                return -1;
            } else {
                logger.info("Scope not found");
                csvFailureRecord(als_file_path, which_command, cmd, "Cannot find after binary search");
                return -1;
            }
        } catch (
                Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            return -1;
        }

    }

    static String successMessage(int i, int scope) {
        return "Success for the " + i + "-th command with overall scope " + scope;
    }

    public static void csvFailureRecord(String file_path, int i, Command command, String reason) throws IOException {
        csvPrinter.printRecord(file_path, i, command, "", "", "", reason);
        csvPrinter.flush();
    }

    public static void csvSuccessRecord(String file_path, int i, Command command, int scope) throws IOException {
        csvPrinter.printRecord(file_path, i, command,
                RunCommand.changeOverallScope(command, scope), scope,
                String.format("%.2f", (float) lastResult.time / 1000000000), lastResult.satisfiable);
        csvPrinter.flush();
    }

    static Integer ExtractModelsFromFile(File file) {
        try {
            Module world = CompUtil.parseEverything_fromFile(null, null, file.getPath());

            // Choose some default options for how you want to execute the commands
            A4Options options = new A4Options();

            options.solver = A4Options.SatSolver.SAT4J;

            int i = randomGenerator.nextInt(world.getAllCommands().size());
            Command command = world.getAllCommands().get(i);
            // If we find a cmd that has startingscope!=endingscope, then let's not include this cmd in our tests
            boolean containsGrowingSig = false;
            for (CommandScope cs : command.scope) {
                if (cs.startingScope != cs.endingScope) {
                    logger.info("Growing sig detected! startingScope != endingScope for command: " + command);
                    containsGrowingSig = true;
                    csvFailureRecord(file.getPath(), i, command, "Growing Sig");
                    break;
                }
            }
            if (containsGrowingSig) return 0;
            // If the command is the default one, it means there are no commands in this file. We skip the file.
            if (command.label.equals("Default") && world.getAllReachableUserDefinedSigs().stream().map(x -> x.label).noneMatch(str -> str.equals("this/Default"))) {
                return 0;
            }
            int scope = binarySearch(file.getPath(), i, command, min_scope, max_scope);
            if (scope == -1) {
                return 0;
            } else {
                logger.info(successMessage(i, scope));
                csvSuccessRecord(file.getPath(), i, command, scope);
            }

            // Print files with new commands in sat and unsat directories
            Path path = file.toPath();
            Charset charset = StandardCharsets.UTF_8;
            String content = FileUtils.readFileToString(file);
            // Remove all comments
            content = content.replaceAll("//.*|--.*|/\\*[\\S\\s]*?\\*/", "");
            // Remove all commands using regular expression
            // name : ["run" or "check"] anything* until the start of next block
            String pattern = "(\\w+\\s*:\\s*|\\b)(check|run)\\b[\\S\\s]*?(?=(" +
                    "(abstract|assert|check|fact|fun|module|none|open|pred|run|((var\\s+)?((lone|some|one)\\s+)?)sig)\\s|\\Z))";
            Pattern r = Pattern.compile(pattern);
            Matcher m = r.matcher(content);
            int index = 0;
            String nameExpr = "";
            while (m.find()) {
                // Extract the Expression of the i-th command to be used later
                if (index == i) {
                    if (m.group().contains("{")) {
                        nameExpr = m.group().substring(m.group().indexOf("{"), m.group().lastIndexOf("}") + 1);
                    }
                    break;
                }
                index++;
            }
            content = content.replaceAll(pattern, "\n");

            // Check if it really works
            pattern = "\\b(run|check)\\b";
            r = Pattern.compile(pattern);
            m = r.matcher(content);
            if (m.find()) {
                logger.warning("Error, there's some old commands left unexpectedly.");
                return 1;
            }

            // Write new commands to the file
            Command new_command = RunCommand.changeOverallScope(command, scope);
            String command_str = new_command.toString();
            // Replace Check and Run with lowercase letters
            if (command_str.contains("Check ")) {
                command_str = command_str.replaceFirst("Check", "check");
            } else if (command_str.contains("Run ")) {
                command_str = command_str.replaceFirst("Run", "run");
            }
            if (!nameExpr.isEmpty()) {
                command_str = command_str.replace(" " + new_command.label + " ", " " + nameExpr + " ");
            } else {
                // For special case:  command_name: check pred_name for 5
                // We want to use pred_name not command_name here
                if (world.getAllReachableSigs().stream().map(x -> x.label).noneMatch(str -> str.contains(new_command.label))) {
                    command_str = command_str.replace(" " + new_command.label + " ", " " + new_command.nameExpr + " ");
                }
            }
            content = content + "\n" + command_str + "\n";
            Files.write(path, content.getBytes(charset));

            if (lastResult.satisfiable.equals("SAT")) {
                num_sat++;
                satfile.write(file.getPath().split(dirname + "/", 2)[1] + "\n");
                satfile.flush();
            } else {
                num_unsat++;
                unsatfile.write(file.getPath().split(dirname + "/", 2)[1] + "\n");
                unsatfile.flush();
            }
            summaryfile.printRecord(file.getPath().split(dirname + "/", 2)[1], lastResult.satisfiable,
                    command_str, scope);
            summaryfile.flush();
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            return 1;
        }
        return 0;
    }

    static void getListFileNames(File[] files) {
        for (File file : files) {
            if (file.isDirectory()) {
                getListFileNames(file.listFiles());
            } else {
                if (FilenameUtils.getExtension(file.getPath()).equals("als")) {
                    file_names.add(file.getPath());
                }
            }
        }
    }

    static Integer ExtractSatUnsatModels() {
        if (file_count > 0) {
            // Resume a paused process
            // logs "restart" timestamp
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
            LocalDateTime now = LocalDateTime.now();
            logger.info("**************** RESTART " + dtf.format(now) + "****************");
            // read the random list of files
            File file = new File(file_of_files);
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = br.readLine()) != null) {
                    file_names.add(line);
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                return 1;
            }
            // Obtain number of sat and unsat models
            try {
                BufferedReader reader = new BufferedReader(new FileReader(file_sat_list));
                while (reader.readLine() != null) num_sat++;
                reader.close();
                reader = new BufferedReader(new FileReader(file_unsat_list));
                while (reader.readLine() != null) num_unsat++;
                reader.close();
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                return 1;
            }
        } else {
            // Get list of files for randomization
            for (File f : new File(dirname).listFiles()) {
                if (f.isDirectory()) {
                    getListFileNames(f.listFiles());
                }
            }
            Collections.shuffle(file_names);

            try {
                // Print out the file list
                File f = new File(file_of_files);
                f.getParentFile().mkdirs();
                BufferedWriter wr = new BufferedWriter(new FileWriter(file_of_files));
                for (String fname : file_names) {
                    wr.write(fname);
                    wr.newLine();
                }
                wr.close();
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                return 1;
            }
        }

        FileWriter csvWriter;
        try {
            // Open the CSV writer
            csvWriter = new FileWriter(dirname + "/commandScopes.csv", true);
            csvPrinter = new CSVPrinter(csvWriter, CSVFormat.DEFAULT.withHeader("File Path", "i-th Command",
                    "Original Command", "New Command", "Overall Scope", "Time", "Satisfiable?"));
            csvWriter = new FileWriter(dirname + "/model_summary.csv", true);
            summaryfile = new CSVPrinter(csvWriter, CSVFormat.DEFAULT.withHeader("File Path", "Satisfiable?", "New " +
                    "Command", "Scope"));

            // Open the .txt files containing sat/unsat model file names
            File f = new File(file_sat_list);
            satfile = new FileWriter(file_sat_list, true);
            f = new File(file_unsat_list);
            unsatfile = new FileWriter(file_unsat_list, true);
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            return 1;
        }

        // Extract models
        for (int i = file_count; i < file_names.size(); i++) {
            String path = file_names.get(i);
            logger.info("RUN NO. " + i + ", " + path);
            if (num_sat >= num_sat_wanted && num_unsat >= num_unsat_wanted) {
                break;
            }
            if (new File(path).exists()) {
                if (ExtractModelsFromFile(new File(path)) == 1) {
                    logger.warning("Abnormal Behaviour! Something bad happened when extracting SAT and UNSAT models.");
                }
            }
        }

        // Delete the original model-set directory
        // Print out models count
        try {
            // Close csv file and .txt files
            csvPrinter.close();
            summaryfile.close();
            satfile.close();
            unsatfile.close();

            readmefile.write("Extracted " + num_sat + " SAT models.\n");
            readmefile.write("Extracted " + num_unsat + " UNSAT models.\n");
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            return 1;
        }
        return 0;
    }

    public static void main(String[] args) {
        // Setup readme file and logger
        if (Setup() == 1) {
            return;
        }

        // Extract sat and unsat models
        if (ExtractSatUnsatModels() == 1) {
            logger.warning("Failed to extract SAT and UNSAT models.");
        }

        try {
            readmefile.close();
        } catch (Exception e) {
            logger.warning("Failed to close readme file");
        }

        logger.info("Completed!");
    }
}
