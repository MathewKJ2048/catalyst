/*
 * Catalyst -- A framework for performance analysis/optimization of Alloy models
 * Copyright (C) 2018-2019 Amin Bandali
 * Modified 2021 Nancy Day to become AlloyModelSetTools
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

import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.*;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;

import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.ast.CommandScope;
import edu.mit.csail.sdg.ast.Module;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import org.kohsuke.github.*;

import static org.apache.commons.codec.digest.MessageDigestAlgorithms.SHA_256;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.codec.binary.Base32;

public class AlloyModelSetTools {
    // Users set these options. You can choose to gather from Github
    // repositories or from existing model sets or both.
    static boolean gatherFromGithub = true;
    // The max number of repos to clone, -1 for downloading all github repos
    static int num_git_repos = 5;
    static boolean gatherFromExistingModelSets = false;
    // List of existing model sets directories to draw from, paths can either
    // be relative or absolute,  relative path are expected to be relative to
    // "alloy-model-sets/". One example: "model-sets/2021-05-07-14-22-48".
    static String[] existing_model_sets = {};
    // Whether to remove non-Alloy files, note that hidden files will also be
    // removed.
    static boolean removeNonAlloyFiles = true;
    // Whether to remove Alloy utility models:
    // boolean.als, integer.als, ordering.als, seqrel.als, sequniv.als,
    // time.als, graph.als, natural.als, relation.als, sequence.als, ternary.als
    static boolean removeUtilModels = true;
    static boolean removeDuplicateFiles = true;
    static boolean removeDoNotParse = true;
    static boolean extractSatUnsatModels = true;
    static long lower_bound_of_time_range_in_seconds = 2 * 60;
    static long higher_bound_of_time_range_in_seconds = 15 * 60;
    // min scope to stop looking for scope
    static int min_scope = 1;
    // max scope to stop looking for scope
    static int max_scope = 200;
    // You don't need to change anything after this line

    // static variables
    static FileWriter readmefile;
    static String dirname;
    static HashMap<String, List<File>> alsFileNames = new HashMap<>();
    static int numAlsFiles = 0;
    static int numFilesFromExisting = 0;
    static int numDuplicateFiles = 0;
    static int numDoNotParse = 0;
    static Logger logger;
    static CSVPrinter csvPrinter;
    static Result lastResult;
    static List<Command> satCommands = new ArrayList<>();
    static List<Command> unsatCommands = new ArrayList<>();
    // stdio is used for error output

    static String sha256(String s) {
        return new Base32().encodeAsString(new DigestUtils(SHA_256).digest(s));
    }

    static String sha256_n(String s, int n) {
        return sha256(s).substring(0, n).toLowerCase();
    }

    static String sha256_32(String s) {
        return sha256_n(s, 32);
    }

    // separate this into different functions
    // each function to add something to the README.md file of the new model-set directory
    // gather: from github
    // gather: from existing model-sets (might be ones others added in here)
    // filter: remove non-Alloy files (only  keep files with .als extension - lower case)
    // filter: remove Alloy util/library models (util.als, ordering.als - there is a standard list)
    // filter: remove duplicate models (filenames or contents of files?)
    // filter: randomly choose one model from each directory (Elias to think about)
    //
    // for the following we need to run alloy
    // filter: remove those that raise an error in Alloy
    // fcn: choose scopes for a model and record these in a .csv (KT and Elias both have some relevant code)
    //      write new alloy files with these scopes (Tamjid has code that writes Alloy models)
    // filter: ones that have transitive closure or other syntactic features (KT has code for this)
    // filter: models that are SAT/UNSAT in kodkod

    public static String getSystemInfo() {
        SystemInfo systemInfo = new SystemInfo();
        HardwareAbstractionLayer hardware = systemInfo.getHardware();

        String s = "This program is running on the following platform:\n";
        s += "Operating System: " + systemInfo.getOperatingSystem().toString() + " : " + System.getProperty("os.arch") + "\n";
        s += "CPU: " + hardware.getProcessor().getProcessorIdentifier().getName() + "\n";
        s += "Memory: " + hardware.getMemory().toString() + "\n";
        return s;
    }

    static Integer CreateModelSetDir() {
        try {
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
            LocalDateTime now = LocalDateTime.now();
            dirname = "model-sets/" + dtf.format(now);
            String readmefilename = dirname + "/" + "README.md";

            File f = new File(readmefilename);
            f.getParentFile().mkdirs();
            readmefile = new FileWriter(readmefilename);
            readmefile.write("Model set created: " + dirname + "\n");

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
            logger.info(getSystemInfo());
            return 0;
        } catch (Exception e) {
            logger.warning("An error occurred creating the model set directory and/or setting up the logger.");
            return 1;
        }
    }

    static Integer GatherFromGithub() {

        // the query searches repositories that have files written in the Alloy language, but excludes repositories
        // that have files written in ableton, midi, music and mIRC (since they also use the .als extension).
        // the query also EXCLUDES the AlloyTools/models repository.
        String query = "language:alloy NOT ableton NOT midi NOT music NOT mIRC";
        boolean showDescriptions = false;
        boolean useSSHUrl = false;    // whether to use the SSH protocol for cloning, or HTTPS
        boolean prependSHA256 = true; // whether to prepend SHA-256 of url to folder name (to obtain unique names)
        boolean makeCloneList = true; // format output as a convenient list of "git clone" commands

        makeCloneList = makeCloneList && !showDescriptions; // only make clone list if showDescriptions is false

        GitHub gh;

        try {
            gh = GitHub.connect();
            if (gh == null) {
                logger.warning("Connection to GitHub failed");
                return 1;
            }
            logger.info("Connected to GitHub");

            // Setting page size to avoid hitting api rate limit
            PagedSearchIterable<GHRepository> repos = gh.searchRepositories().q(query).list().withPageSize(100);

            logger.info("Search query: " + query);
            int numresults = repos.getTotalCount();
            logger.info("# of results: " + numresults);

            // Build an array of indexes to randomize randomize order of list of git repos to draw from
            // We didn't shuffle the search result directly because it is taking too long to convert
            // it into a list
            List<Integer> range = new ArrayList<Integer>(numresults) {{
                for (int i = 1; i < numresults + 1; i++)
                    this.add(i);
            }};
            Collections.shuffle(range);
            if (num_git_repos == -1 || numresults < num_git_repos)
                num_git_repos = numresults;
            range = range.subList(0, num_git_repos);

            int loopIndex = 1;
            StringBuilder gitCloneString = new StringBuilder();
            for (GHRepository repo : repos) {
                if (range.contains(loopIndex)) {
                    String str = makeCloneList ? "git clone" : "";

                    String url = useSSHUrl ? repo.getSshUrl() : repo.getHttpTransportUrl();
                    str += " " + url;

                    if (showDescriptions)
                        str += " " + repo.getDescription();
                    else if (makeCloneList) {
                        str += " ";
                        if (prependSHA256)
                            str += sha256_32(url) + "-";
                        str += repo.getName();
                    }
                    logger.info(str);
                    gitCloneString.append(str);
                    gitCloneString.append("\n");
                }
                loopIndex++;
            }

            // Now we have a giant string of "git clone"s
            // We use Java's process builder to do the git cloning
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", String.valueOf(gitCloneString));
            pb.directory(new File(dirname));
            pb.inheritIO();
            pb.redirectErrorStream(true);
            try {
                Process process = pb.start();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info(line);
                }
                reader.close();
                int exitVal = process.waitFor();
                if (exitVal != 0) {
                    logger.warning("Abnormal Behaviour! Something bad happened when git cloning repositories.");
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
            }

            // write to readme github query used
            readmefile.write("Scraped from " + num_git_repos + " github repos with query: " + query + "\n");
            return 0;
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            return 1;
        }
    }

    static Integer GatherFromExistingModelSets() {
        try {
            // write to readme which directories gathered from
            readmefile.write("Gathered from " + existing_model_sets.length +
                    " existing model sets directories:\n");
            for (String dir : existing_model_sets) {
                File srcDir = new File(dir);
                File destDir = new File(dirname + "/" + srcDir.getName());

                readmefile.write("README.md in " + dir + ":\n");
                if (new File(dir + "/README.md").exists()) {
                    BufferedReader in = new BufferedReader(new FileReader(new File(dir + "/README.md")));
                    String str;
                    while ((str = in.readLine()) != null) {
                        readmefile.write("    " + str + "\n");
                    }
                    in.close();
                }
                readmefile.write("\n");

                // Copy directory contents
                ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                        "cp -r " + Paths.get(dirname).relativize(srcDir.toPath()) + " "
                                + Paths.get(dirname).relativize(destDir.toPath()));
                pb.directory(new File(dirname));
                pb.inheritIO();
                pb.redirectErrorStream(true);
                try {
                    Process process = pb.start();
                    int exitVal = process.waitFor();
                    if (exitVal != 0) {
                        logger.warning("Abnormal Behaviour! Something bad happened when cleaning up files.");
                        return 1;
                    }
                } catch (Exception e) {
                    logger.log(Level.SEVERE, e.getMessage(), e);
                    return 1;
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            return 1;
        }
        return 0;
    }

    public static void GetAllDuplicateFiles(File[] files) {
        for (File file : files) {
            if (file.isDirectory()) {
                GetAllDuplicateFiles(file.listFiles());
            } else {
                if (!alsFileNames.containsKey(file.getName())) {
                    alsFileNames.put(file.getName(), new ArrayList<>());
                }
                alsFileNames.get(file.getName()).add(file);
            }
        }
    }


    public static void RemoveDuplicateFiles(String dirname) {
        // Get all files with duplicate names
        for (File f : new File(dirname).listFiles()) {
            if (f.isDirectory()) {
                GetAllDuplicateFiles(f.listFiles());
            }
        }
        Random randomGenerator = new Random();
        for (List<File> files : alsFileNames.values()) {
            if (files.size() > 1) {
                // Look at the file size
                HashMap<Long, List<File>> fileSizes = new HashMap<>();
                for (File f : files) {
                    long file_size = f.length();
                    if (!fileSizes.containsKey(file_size)) {
                        fileSizes.put(file_size, new ArrayList<>());
                    }
                    fileSizes.get(file_size).add(f);
                }
                for (List<File> fs : fileSizes.values()) {
                    if (fs.size() > 1) {
                        // Randomly select one to keep, and delete the other ones
                        int index = randomGenerator.nextInt(fs.size());
                        for (int i = 0; i < fs.size(); i++) {
                            if (i != index) {
                                numDuplicateFiles++;
                                logger.info("Duplicate: " + fs.get(i).getPath());
                                if (!fs.get(i).delete()) {
                                    logger.warning("Abnormal Behaviour! Something bad happened when deleting duplicate files.");
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public static void RemoveDoNotParse(File[] files) {
        for (File file : files) {
            if (file.isDirectory()) {
                RemoveDoNotParse(file.listFiles());
                // Calls  same method again.
            } else {
                // Parse+typecheck the model
                logger.info("=========== Parsing+Typechecking " + file.getPath() + " =============");
                try {
                    Module world = CompUtil.parseEverything_fromFile(null, null, file.getPath());

                } catch (Exception e) {
                    logger.log(Level.INFO, e.getMessage(), e);
                    logger.info(file.getPath() + " do not parse");
                    numDoNotParse++;
                    if (!file.delete()) {
                        logger.warning("Abnormal Behaviour! Something bad happened when deleting files do not parse.");
                    }
                    alsFileNames.remove(file.getName());
                }
            }
        }
    }

    static Integer CleanUpFiles() {
        String cleanUpCommands = "";
        if (removeNonAlloyFiles) {
            cleanUpCommands += "rm -rf .*\n"; // remove hidden files
            cleanUpCommands += "find . -mindepth 2 -depth -type f ! -name '*.als' -delete\n";
        }
        if (removeUtilModels)
            cleanUpCommands += "find . -depth -type f \\( -name 'boolean.als' -o -name 'graph.als' -o -name 'integer.als' -o -name 'natural.als' -o -name 'ordering.als' -o -name 'relation.als' -o -name 'seqrel.als' -o -name 'sequence.als' -o -name 'sequniv.als' -o -name 'ternary.als' -o -name 'time.als' \\) -print -delete | wc -l | tr -d ' ' | awk '{print \"Number of removed files: \"$1}'\n";
        cleanUpCommands += "find . -type d -empty -delete\n"; // remove empty folders

        int numUtilFiles = 0;
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", cleanUpCommands);
        pb.directory(new File(dirname));
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("Number of removed files:")) {
                    numUtilFiles = Integer.parseInt(line.split(" ")[4]);
                }
                logger.info(line);
            }
            reader.close();
            int exitVal = process.waitFor();
            if (exitVal != 0) {
                logger.warning("Abnormal Behaviour! Something bad happened when cleaning up files.");
                return 1;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            return 1;
        }

        if (removeUtilModels) {
            try {
                readmefile.write("Removed " + numUtilFiles + " util files" + "\n");
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                return 1;
            }
        }

        if (removeDuplicateFiles) {
            RemoveDuplicateFiles(dirname);
            try {
                readmefile.write("Removed " + numDuplicateFiles + " duplicate files" + "\n");
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                return 1;
            }
        }

        if (removeDoNotParse) {
            for (File f : new File(dirname).listFiles()) {
                if (f.isDirectory()) {
                    RemoveDoNotParse(f.listFiles());
                }
            }

            try {
                readmefile.write("Removed " + numDoNotParse + " files that " +
                        "do not parse." + "\n");
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                return 1;
            }
        }

        // remove empty folders
        pb = new ProcessBuilder("bash", "-c", "find . -type d -empty -delete\n");
        pb.directory(new File(dirname));
        pb.inheritIO();
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            int exitVal = process.waitFor();
            if (exitVal != 0) {
                logger.warning("Abnormal Behaviour! Something bad happened when cleaning up files.");
                return 1;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            return 1;
        }

        return 0;
    }

    static void CountFiles(File[] files, boolean isFromExisting) {
        for (File file : files) {
            if (file.isDirectory()) {
                CountFiles(file.listFiles(), isFromExisting);
            } else {
                numAlsFiles++;
                if (isFromExisting) numFilesFromExisting++;
            }
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
                } else if (line.contains("java.lang.OutOfMemoryError")) {
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
    static Integer binarySearch(String als_file_path, int which_command,
                                int min_scope, int max_scope) {
        if (max_scope >= min_scope) {
            int mid_scope = min_scope + (max_scope - min_scope) / 2;
            lastResult = runCommand(als_file_path, which_command, mid_scope);
            Status exitStatus = lastResult.status;
            // If mid_scope is what we want
            if (exitStatus == Status.SUCCESS)
                return mid_scope;

            // If mid_scope is takiRng too short, then it can only be present
            // in smaller scopes
            if (exitStatus == Status.TIMEOUT)
                return binarySearch(als_file_path, which_command, min_scope, mid_scope - 1);

            if (exitStatus == Status.EXCEPTION || exitStatus == Status.UNKNOWN) {
                logger.warning("Exception or unknown error thrown when doing binary search with scope " + mid_scope);
                return -1;
            }

            // Else we will search for larger scopes
            return binarySearch(als_file_path, which_command, mid_scope + 1, max_scope);
        }

        // We reach here when no scope in the range have desired execution time
        return -1;
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
        if (lastResult.satisfiable.equals("SAT")) {
            satCommands.add(RunCommand.changeOverallScope(command, scope));
        } else if (lastResult.satisfiable.equals("UNSAT")) {
            unsatCommands.add(RunCommand.changeOverallScope(command, scope));
        }
    }

    public static void printBinarySearchResult(int scope, String file_path, int i, Command command) throws IOException {
        if (scope == -1) {
            logger.info("Scope not found");
            csvFailureRecord(file_path, i, command, "Not Found after binary search");
        } else {
            logger.info(successMessage(i, scope));
            csvSuccessRecord(file_path, i, command, scope);
        }
    }

    static Integer ExtractModelsFromFile(File[] files) {
        for (File file : files) {
            if (file.isDirectory()) {
                ExtractModelsFromFile(file.listFiles());
            } else {
                try {
                    Module world = CompUtil.parseEverything_fromFile(null, null, file.getPath());

                    // Choose some default options for how you want to execute the commands
                    A4Options options = new A4Options();

                    options.solver = A4Options.SatSolver.SAT4J;

                    satCommands.clear();
                    unsatCommands.clear();
                    for (int i = 0; i < world.getAllCommands().size(); i++) {
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
                        if (containsGrowingSig) continue;
                        lastResult = runCommand(file.getPath(), i, -1);
                        switch (lastResult.status) {
                            case SUCCESS:
                                logger.info("Success for the " + i + "-th command with the original command!");
                                csvPrinter.printRecord(file.getPath(), i, command, "same", command.overall,
                                        String.format("%.2f", (float) lastResult.time / 1000000000), lastResult.satisfiable);
                                csvPrinter.flush();
                                if (lastResult.satisfiable.equals("SAT")) {
                                    satCommands.add(command);
                                } else if (lastResult.satisfiable.equals("UNSAT")) {
                                    unsatCommands.add(command);
                                }
                                break;
                            case TOOSHORT:
                                if (command.overall == -1) {
                                    // Try the maximum scope first, if it is still too short, do nothing.
                                    lastResult = runCommand(file.getPath(), i, max_scope);
                                    Status maxScopeStatus = lastResult.status;
                                    if (maxScopeStatus == Status.SUCCESS) {
                                        logger.info(successMessage(i, max_scope));
                                        csvSuccessRecord(file.getPath(), i, command, max_scope);
                                    } else if (maxScopeStatus != Status.TOOSHORT) {
                                        int scope = binarySearch(file.getPath(), i, Math.max(4, min_scope), max_scope);
                                        printBinarySearchResult(scope, file.getPath(), i, command);
                                    } else {
                                        logger.info("Cannot find scope under specified max scope for the " + i + "-th command!");
                                        csvFailureRecord(file.getPath(), i, command, "Cannot find under max scope");
                                    }
                                } else {
                                    Status originalScopeStatus = Status.TOOSHORT;
                                    // If it has any individual scopes or exact scope, drop all individual scopes and
                                    // exact scopes and run this command again
                                    if (command.scope.size() != 0) {
                                        lastResult = runCommand(file.getPath(), i, command.overall);
                                        originalScopeStatus = lastResult.status;
                                    }
                                    int scope;
                                    switch (originalScopeStatus) {
                                        case SUCCESS:
                                            logger.info(successMessage(i, command.overall));
                                            csvSuccessRecord(file.getPath(), i, command, command.overall);
                                            break;
                                        case TOOSHORT:
                                            // Try the maximum scope first, if it is still too short, do nothing.
                                            lastResult = runCommand(file.getPath(), i, max_scope);
                                            Status maxScopeStatus = lastResult.status;
                                            if (maxScopeStatus == Status.SUCCESS) {
                                                logger.info(successMessage(i, max_scope));
                                                csvSuccessRecord(file.getPath(), i, command, max_scope);
                                            } else if (maxScopeStatus != Status.TOOSHORT) {
                                                scope = binarySearch(file.getPath(), i, command.overall + 1, max_scope);
                                                printBinarySearchResult(scope, file.getPath(), i, command);
                                            } else {
                                                logger.info("Cannot find scope under specified max scope for the " + i + "-th command!");
                                                csvFailureRecord(file.getPath(), i, command, "Cannot find under max scope");
                                            }
                                            break;
                                        case TIMEOUT:
                                            scope = binarySearch(file.getPath(), i, min_scope, command.overall - 1);
                                            printBinarySearchResult(scope, file.getPath(), i, command);
                                            break;
                                        case EXCEPTION:
                                            logger.info("Exception thrown!");
                                            csvFailureRecord(file.getPath(), i, command, "Exception");
                                            break;
                                        case UNKNOWN:
                                            logger.warning("Unknown Error! The code should never reach here!");
                                            csvFailureRecord(file.getPath(), i, command, "Unknown");
                                            break;
                                    }
                                }
                                break;
                            case TIMEOUT:
                                if (command.overall == -1) {
                                    int scope = binarySearch(file.getPath(), i, min_scope, 2);
                                    printBinarySearchResult(scope, file.getPath(), i, command);
                                } else {
                                    Status originalScopeStatus = Status.TIMEOUT;
                                    // If it has any individual scopes or exact scope, drop all individual scopes and
                                    // exact scopes and run this command again
                                    if (command.scope.size() != 0) {
                                        lastResult = runCommand(file.getPath(), i, command.overall);
                                        originalScopeStatus = lastResult.status;
                                    }
                                    int scope;
                                    switch (originalScopeStatus) {
                                        case SUCCESS:
                                            logger.info(successMessage(i, command.overall));
                                            csvSuccessRecord(file.getPath(), i, command, command.overall);
                                            break;
                                        case TOOSHORT:
                                            scope = binarySearch(file.getPath(), i, command.overall + 1, max_scope);
                                            printBinarySearchResult(scope, file.getPath(), i, command);
                                            break;
                                        case TIMEOUT:
                                            scope = binarySearch(file.getPath(), i, min_scope, command.overall - 1);
                                            printBinarySearchResult(scope, file.getPath(), i, command);
                                            break;
                                        case EXCEPTION:
                                            logger.info("Exception thrown!");
                                            csvFailureRecord(file.getPath(), i, command, "Exception");
                                            break;
                                        case UNKNOWN:
                                            logger.warning("Unknown Error! The code should never reach here!");
                                            csvFailureRecord(file.getPath(), i, command, "Unknown");
                                            break;
                                    }
                                }
                                break;
                            case EXCEPTION:
                                logger.info("Exception thrown!");
                                csvFailureRecord(file.getPath(), i, command, "Exception");
                                break;
                            case UNKNOWN:
                                logger.warning("Unknown Error! The code should never reach here!");
                                csvFailureRecord(file.getPath(), i, command, "Unknown");
                                break;
                        }
                    }
                    csvPrinter.flush();
                } catch (Exception e) {
                    logger.log(Level.SEVERE, e.getMessage(), e);
                    return 1;
                }
            }
        }
        return 0;
    }

    static Integer ExtractSatUnsatModels() {
        // Open the CSV writer
        FileWriter csvWriter;
        try {
            csvWriter = new FileWriter(dirname + "/commandScopes.csv");
            csvPrinter = new CSVPrinter(csvWriter, CSVFormat.DEFAULT.withHeader("File Path", "i-th Command",
                    "Original Command", "New Command", "Overall Scope", "Time", "Satisfiable?"));
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            return -1;
        }

        // Extract models
        for (File f : new File(dirname).listFiles()) {
            if (f.isDirectory()) {
                if (ExtractModelsFromFile(f.listFiles()) == 1) {
                    logger.warning("Abnormal Behaviour! Something bad happened when extracting SAT and UNSAT models.");
                }
            }
        }

        // TODO: Delete the original model-set directory
        // Print out models count
        try {
            // Close csv file
            csvWriter.close();

            numAlsFiles = 0;
            if (new File(dirname + "/sat").exists()) {
                for (File f : new File(dirname + "/sat").listFiles()) {
                    if (f.isDirectory()) {
                        CountFiles(f.listFiles(), false);
                    }
                }
            }
            readmefile.write("\nExtracted " + numAlsFiles + " SAT models.\n");
            numAlsFiles = 0;
            if (new File(dirname + "/unsat").exists()) {
                for (File f : new File(dirname + "/unsat").listFiles()) {
                    if (f.isDirectory()) {
                        CountFiles(f.listFiles(), false);
                    }
                }
            }
            readmefile.write("\nExtracted " + numAlsFiles + " UNSAT models.\n");
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            return -1;
        }
        return 0;
    }

    static void printNumOfFiles() {
        HashSet<String> existing_model_sets_name = new HashSet<>();
        for (String path : existing_model_sets) {
            existing_model_sets_name.add(Paths.get(path).getFileName().toString());
        }
        for (File f : new File(dirname).listFiles()) {
            if (f.isDirectory()) {
                CountFiles(f.listFiles(), existing_model_sets_name.contains(f.getName()));
            }
        }

        try {
            String outputCount = "Total " + numAlsFiles + " .als " +
                    "files.\n";

            if (gatherFromGithub && gatherFromExistingModelSets) {
                outputCount += numAlsFiles - numFilesFromExisting + " " +
                        ".als files drawn from " + num_git_repos + " github " +
                        "repos and " + numFilesFromExisting + " .als files " +
                        "drawn from " + existing_model_sets.length + " " +
                        "existing model set directories.";
            } else if (gatherFromGithub) {
                outputCount += numAlsFiles - numFilesFromExisting + " " +
                        ".als files drawn from " + num_git_repos + " github " +
                        "repos.";
            } else if (gatherFromExistingModelSets) {
                outputCount += numFilesFromExisting + " .als files " +
                        "drawn from " + existing_model_sets.length + " " +
                        "existing model set directories.";
            }
            readmefile.write(outputCount + "\n");
        } catch (Exception e) {
            logger.warning("Failed to write file count to readme file");
        }
    }

    public static void main(String[] args) {

        // Start a new model set directory
        // expects to be run in root of alloy-models-sets directory
        if (CreateModelSetDir() == 1) {
            return;
        }

        if (gatherFromGithub) {
            // Gather from github
            if (GatherFromGithub() == 1) {
                logger.warning("Failed to gather models from github");
                return;
            }
        }

        // Gather from existing models-sets
        if (gatherFromExistingModelSets) {
            if (GatherFromExistingModelSets() == 1) {
                logger.warning("Failed to gather models from existing model sets");
                return;
            }
        }

        // Remove non-Alloy files, Alloy util/library models and duplicate models if options set
        if (CleanUpFiles() == 1) {
            logger.warning("Failed to remove not needed files");
            return;
        }

        printNumOfFiles();

        // Extract sat and unsat models
        if (extractSatUnsatModels) {
            if (ExtractSatUnsatModels() == 1) {
                logger.warning("Failed to extract SAT and UNSAT models.");
            }
        }

        try {
            readmefile.close();
        } catch (Exception e) {
            logger.warning("Failed to close readme file");
        }
    }
}
