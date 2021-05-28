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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
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
import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;

import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.ast.CommandScope;
import edu.mit.csail.sdg.ast.Module;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import org.kohsuke.github.*;

import static java.lang.Math.min;
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
    static boolean removeMultipleVersion = true;
    static boolean removeDoNotParse = true;
    // Remove files with common file names to avoid extracting models with high
    // similarity, like those in Jackson's book.
    static boolean hitlistFilter = true;
    static String jackson_model_dir = "2021-05-25-13-24-28-jackson";
    static String[] jackson_model_names = {"abstractMemory", "addressBook", "barbers", "cacheMemory", "checkCache",
            "checkFixedSize", "closure", "distribution", "filesystem", "fixedSizeMemory", "grandpa", "hotel", "lights",
            "lists", "mediaAssets", "phones", "prison", "properties", "ring", "sets", "spanning", "tree", "tube", "undirected"};
    // Put additional file names (other than Jackson's) you also want to filter
    // on here. For each name, only one model containing it will be kept.
    // For example, "ownGrandpa".
    static String[] additional_common_file_names = {};
    static boolean extractSatUnsatModels = true;
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
    static FileWriter readmefile;
    static FileWriter satfile;
    static FileWriter unsatfile;
    static String dirname;
    static HashMap<String, List<File>> alsFileNames = new HashMap<>();
    static int numAlsFiles = 0;
    static int numFilesFromExisting = 0;
    static int numFilesRemoved = 0;
    static HashSet<String> files_encountered = new HashSet<>();
    static Logger logger;
    static CSVPrinter csvPrinter;
    static Result lastResult;
    static List<String> file_names = new ArrayList<String>();
    static int num_sat = 0;
    static int num_unsat = 0;
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
                                numFilesRemoved++;
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

    // Returns the prefix of a string until a number or a ".als";
    public static String prefixOf(String str) {
        int i = 0;
        while (i < str.length() && !Character.isDigit(str.charAt(i))) i++;
        // If it starts with a number, return the entire string as prefix so that it won't be able to match anything
        if (i == 0) return str;
        i = min(i, str.split(".als", 2)[0].length());
        return str.substring(0, i);
    }

    // Remove multiple version files in one directory
    public static void RemoveMultipleVersionInDirectory(File file) {
        // Sort all file names in alphabetical order
        List<String> f_names = new ArrayList<String>();
        for (File f : file.listFiles()) {
            if (!f.isDirectory()) {
                f_names.add(f.getName());
            }
        }
        Collections.sort(f_names);
        // Iterate through the sorted list.
        // If it has the same prefix as previous file, discard the previous one;
        // Otherwise, make this new prefix what we are comparing against.
        if (!f_names.isEmpty()) {
            String prefix = f_names.get(0) + "not";
            String prev = f_names.get(0);
            for (String f_name : f_names) {
                if (prefixOf(f_name).equals(prefix)) {
                    logger.info(file.getPath() + "/" + prev + " removed by the multiple version filter.");
                    if (!new File(file.getPath() + "/" + prev).delete()) {
                        logger.warning("Abnormal Behaviour! Something bad happened when deleting files do not parse.");
                    }
                } else {
                    prefix = prefixOf(f_name);
                }
                prev = f_name;
            }
        }
    }


    public static void RemoveMultipleVersion(File[] files) {
        // Get all files with duplicate names
        for (File f : files) {
            if (f.isDirectory()) {
                RemoveMultipleVersion(f.listFiles());
                RemoveMultipleVersionInDirectory(f);
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
                    numFilesRemoved++;
                    if (!file.delete()) {
                        logger.warning("Abnormal Behaviour! Something bad happened when deleting files do not parse.");
                    }
                    alsFileNames.remove(file.getName());
                }
            }
        }
    }

    public static void HitlistFilter(File[] files) {
        for (File file : files) {
            if (file.isDirectory()) {
                HitlistFilter(file.listFiles());
            } else {
                String fname = file.getName();
                if (Arrays.stream(jackson_model_names).anyMatch(fname::contains)) {
                    // if it is a filename in Jackson's original repo we discard this file
                    if (!file.delete()) {
                        logger.warning("Abnormal Behaviour! Something bad happened when deleting files in hitlist.");
                    }
                    logger.info(file.getPath() + " removed by the hitlist filter");
                    numFilesRemoved++;
                } else {
                    // if it is not a filename in Jackson's original repo, keep the first one we encounter and then no more of that name on the hitlist
                    Optional<String> common_name = Arrays.stream(additional_common_file_names).filter(fname::contains).findFirst();
                    if (common_name.isPresent()) {
                        if (files_encountered.contains(common_name.get())) {
                            if (!file.delete()) {
                                logger.warning("Abnormal Behaviour! Something bad happened when deleting files in hitlist.");
                            }
                            logger.info(file.getPath() + " removed by the hitlist filter");
                            numFilesRemoved++;
                        } else {
                            files_encountered.add(common_name.get());
                        }
                    }
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
            numFilesRemoved = 0;
            RemoveDuplicateFiles(dirname);
            try {
                readmefile.write("Removed " + numFilesRemoved + " duplicate files" + "\n");
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                return 1;
            }
        }

        if (removeMultipleVersion) {
            numFilesRemoved = 0;
            RemoveMultipleVersion(new File(dirname).listFiles());

            try {
                readmefile.write("Removed " + numFilesRemoved + " files that " +
                        "might be an earlier version of another file." + "\n");
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                return 1;
            }
        }

        if (removeDoNotParse) {
            numFilesRemoved = 0;
            for (File f : new File(dirname).listFiles()) {
                if (f.isDirectory()) {
                    RemoveDoNotParse(f.listFiles());
                }
            }

            try {
                readmefile.write("Removed " + numFilesRemoved + " files that " +
                        "do not parse." + "\n");
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                return 1;
            }
        }

        if (hitlistFilter) {
            numFilesRemoved = 0;
            for (File f : new File(dirname).listFiles()) {
                // Skip the directory containing jackson's original models
                if (f.isDirectory() && !f.getName().equals(jackson_model_dir)) {
                    HitlistFilter(f.listFiles());
                }
            }

            try {
                readmefile.write("Removed " + numFilesRemoved + " files whose name is in hitlist." + "\n");
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

            // If mid_scope is takiRng too short, then it can only be present
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
            if (max_scope < AlloyModelSetTools.min_scope) {
                logger.info("Scope not found after searching for " + min_scope);
                csvFailureRecord(als_file_path, which_command, cmd, "Scope not found above " + min_scope);
                return -1;
            } else if (min_scope > AlloyModelSetTools.max_scope) {
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
            content = content.replaceAll("//.*\\n|--.*\\n|/\\*[\\S\\s]*?\\*/", "\n");
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
            } else {
                num_unsat++;
                unsatfile.write(file.getPath().split(dirname + "/", 2)[1] + "\n");
            }
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
                file_names.add(file.getPath());
            }
        }
    }

    static Integer ExtractSatUnsatModels() {
        // Get list of files for randomization
        for (File f : new File(dirname).listFiles()) {
            if (f.isDirectory()) {
                getListFileNames(f.listFiles());
            }
        }
        Collections.shuffle(file_names);


        FileWriter csvWriter;
        try {
            // Open the CSV writer
            csvWriter = new FileWriter(dirname + "/commandScopes.csv");
            csvPrinter = new CSVPrinter(csvWriter, CSVFormat.DEFAULT.withHeader("File Path", "i-th Command",
                    "Original Command", "New Command", "Overall Scope", "Time", "Satisfiable?"));

            // Open the .txt files containing sat/unsat model file names
            File f = new File(dirname + "/sat_models.txt");
            satfile = new FileWriter(dirname + "/sat_models.txt");
            f = new File(dirname + "/unsat_models.txt");
            unsatfile = new FileWriter(dirname + "/unsat_models.txt");
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            return -1;
        }

        // Extract models
        for (String path : file_names) {
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
            // Close csv file and .tet files
            csvWriter.close();
            satfile.close();
            unsatfile.close();

            readmefile.write("Extracted " + num_sat + " SAT models.\n");
            readmefile.write("Extracted " + num_unsat + " UNSAT models.\n");
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            return -1;
        }
        return 0;
    }

    static void printNumOfFiles() {
        numAlsFiles = 0;
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
