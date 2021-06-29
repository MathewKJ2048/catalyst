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

import edu.mit.csail.sdg.ast.Module;
import edu.mit.csail.sdg.parser.CompUtil;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;


// recreate model set from a supplied csv file
public class RecreateModelSet {
    // Users set these options.
    // Path to the model_summary file can either be relative or absolute, relative path are expected tobe relative to 
    // "catalyst/". One example: "model-sets/model_summary.csv".
    static String pathToSummaryFile = "model-sets/model_summary.csv";
    // You don't need to change anything after this line

    // Override other settings, turn off all filters with randomness
    static boolean gatherFromGithub = true;
    // The max number of repos to clone, -1 for downloading all github repos
    static int num_git_repos = -1;
    static boolean gatherFromExistingModelSets = false;
    // List of existing model sets directories to draw from, paths can either be relative or absolute,  relative path
    // are expected to be relative to "alloy-model-sets/". One example: "model-sets/2021-05-07-14-22-48".
    static String[] existing_model_sets = {};
    static boolean downloadPlatinumModelSet = true;
    // Whether to remove non-Alloy files, note that hidden files will also be removed.
    static boolean removeNonAlloyFiles = true;
    // Whether to remove Alloy utility models:
    // boolean.als, integer.als, ordering.als, seqrel.als, sequniv.als, time.als, graph.als, natural.als, relation
    // .als, sequence.als, ternary.als
    static boolean removeUtilModels = true;
    static boolean removeDoNotParse = true;
    // Remove files with common file names to avoid extracting models with high similarity, like those in Jackson's 
    // book.

    // static variables
    static FileWriter readmefile;
    static FileWriter satfile;
    static FileWriter unsatfile;
    static CSVPrinter summaryfile;
    static String dirname;
    static HashMap<String, List<File>> alsFileNames = new HashMap<>();
    static int numAlsFiles = 0;
    static int numFilesFromExisting = 0;
    static int numFilesRemoved = 0;
    static Logger logger;
    static int num_sat = 0;
    static int num_unsat = 0;
    // stdio is used for error output

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
            logger.info(util.getSystemInfo());
            return 0;
        } catch (Exception e) {
            logger.warning("An error occurred creating the model set directory and/or setting up the logger.");
            return 1;
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


        // remove empty folders
        pb = new ProcessBuilder("bash", "-c", "find . -type d -empty -delete\n");
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

    static Integer recreateModelSet() {
        FileWriter csvWriter;
        try {
            // Open the CSV reader
            Reader in = new FileReader(pathToSummaryFile);
            Iterable<CSVRecord> records = CSVFormat.DEFAULT
                    .withHeader("File Path", "Satisfiable?", "New Command", "Scope")
                    .withFirstRecordAsHeader()
                    .parse(in);
            csvWriter = new FileWriter(dirname + "/result.csv");
            summaryfile = new CSVPrinter(csvWriter, CSVFormat.DEFAULT.withHeader("File Path", "Status"));

            // Open the .txt files containing sat/unsat model file names
            File f = new File(dirname + "/sat_models.txt");
            satfile = new FileWriter(dirname + "/sat_models.txt");
            f = new File(dirname + "/unsat_models.txt");
            unsatfile = new FileWriter(dirname + "/unsat_models.txt");

            for (CSVRecord record : records) {
                String file_path = record.get("File Path");
                String satisfiable = record.get("Satisfiable?");
                String command_str = record.get("New Command");

                File file = new File(dirname + "/" + file_path);
                if (!file.exists()) {
                    summaryfile.printRecord(file_path, "file not found");
                    summaryfile.flush();
                    continue;
                }
                try {
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
                    content = content.replaceAll(pattern, "\n");
                    content = content + "\n" + command_str + "\n";
                    Files.write(path, content.getBytes(charset));

                    if (satisfiable.equals("SAT")) {
                        num_sat++;
                        satfile.write(file.getPath().split(dirname + "/", 2)[1] + "\n");
                    } else {
                        num_unsat++;
                        unsatfile.write(file.getPath().split(dirname + "/", 2)[1] + "\n");
                    }
                    summaryfile.printRecord(file_path, "success");
                    summaryfile.flush();
                } catch (Exception e) {
                    logger.log(Level.SEVERE, e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            return 1;
        }

        // Delete the original model-set directory
        // Print out models count
        try {
            // Close csv file and .txt files
            satfile.close();
            unsatfile.close();
            summaryfile.close();

            readmefile.write("Recreated " + num_sat + " SAT models.\n");
            readmefile.write("Recreated " + num_unsat + " UNSAT models.\n");
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            return 1;
        }
        return 0;
    }

    public static void main(String[] args) {

        // Start a new model set directory
        // expects to be run in root of alloy-models-sets directory
        if (CreateModelSetDir() == 1) {
            return;
        }

        if (gatherFromGithub) {
            // Gather from github
            if (util.GatherFromGithub(num_git_repos, logger, dirname, readmefile) == 1) {
                logger.warning("Failed to gather models from github");
                return;
            }
        }

        if (downloadPlatinumModelSet) {
            // Gather from github
            if (util.DownloadPlatinumFromGoogleDrive(logger, dirname, readmefile) == 1) {
                logger.warning("Failed to download Platinum model set from Google Drive");
                return;
            }
        }

        // Remove non-Alloy files, Alloy util/library models and duplicate models if options set
        if (CleanUpFiles() == 1) {
            logger.warning("Failed to remove not needed files");
            return;
        }

        // Gather from existing models-sets
        if (gatherFromExistingModelSets) {
            if (util.GatherFromExistingModelSets(existing_model_sets, logger, dirname, readmefile) == 1) {
                logger.warning("Failed to gather models from existing model sets");
                return;
            }
            // Remove non-Alloy files
            if (removeNonAlloyFiles) {
                if (util.RemoveNonAlloyFiles(logger, dirname) == 1) {
                    logger.warning("Failed to remove non-alloy files.");
                    return;
                }
            }
        }

        printNumOfFiles();

        if (recreateModelSet() == 1) {
            logger.warning("Failed to recreate model set");
            return;
        }

        try {
            readmefile.close();
        } catch (Exception e) {
            logger.warning("Failed to close readme file");
        }
    }
}
