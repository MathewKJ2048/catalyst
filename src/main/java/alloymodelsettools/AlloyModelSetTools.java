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

import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.*;

import org.apache.commons.io.FileUtils;
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
    // You don't need to change anything after this line

    // static variables
    static FileWriter readmefile;
    static String dirname;
    static HashSet<String> alsFileNames = new HashSet<>();
    static int numDuplicateFiles = 0;
    static int numFilesFromExisting = 0;
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
            return 0;
        } catch (Exception e) {
            System.out.println("An error occurred creating the model set directory and/or README.md file");
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
                System.out.println("Connection to GitHub failed");
                return 1;
            }
            System.out.println("Connected to GitHub");

            // Setting page size to avoid hitting api rate limit
            PagedSearchIterable<GHRepository> repos = gh.searchRepositories().q(query).list().withPageSize(100);

            System.out.println("Search query: " + query);
            int numresults = repos.getTotalCount();
            System.out.println("# of results: " + numresults);

            // Build an array of indexes to randomize randomize order of list of git repos to draw from
            // We didn't shuffle the search result directly because it is taking too long to convert
            // it into a list
            List<Integer> range = new ArrayList<>(numresults) {{
                for (int i = 1; i < numresults; i++)
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
                    System.out.println(str);
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
                    System.out.println(line);
                }
                reader.close();
                int exitVal = process.waitFor();
                if (exitVal != 0) {
                    System.out.println("Abnormal Behaviour! Something bad happened when git cloning repositories.");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            // write to readme github query used
            readmefile.write("Scraped from " + num_git_repos + " github repos with query: " + query + "\n");
            return 0;
        } catch (Exception e) {
            e.printStackTrace();
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
                        readmefile.write(str + "\n");
                    }
                    in.close();
                    readmefile.write("\n");
                }
                FileUtils.copyDirectory(srcDir, destDir);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return 1;
        }
        return 0;
    }

    public static void RemoveDuplicateFiles(File[] files,
                                            boolean isFromExisting) {
        for (File file : files) {
            if (file.isDirectory()) {
                RemoveDuplicateFiles(file.listFiles(), isFromExisting);
                // Calls  same method again.
            } else {
                if (alsFileNames.contains(file.getName())) {
                    numDuplicateFiles++;
                    System.out.println("Duplicate: " + file.getPath());
                    if (!file.delete()) {
                        System.out.println("Abnormal Behaviour! Something bad happened when deleting duplicate files.");
                    }
                } else {
                    alsFileNames.add(file.getName());
                    if (isFromExisting) numFilesFromExisting++;
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
                System.out.println(line);
            }
            reader.close();
            int exitVal = process.waitFor();
            if (exitVal != 0) {
                System.out.println("Abnormal Behaviour! Something bad happened when cleaning up files.");
                return 1;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return 1;
        }

        try {
            // write to readme github query used
            readmefile.write("Removed " + numUtilFiles + " util files" + "\n");
        } catch (Exception e) {
            e.printStackTrace();
            return 1;
        }

        if (removeDuplicateFiles) {
            HashSet<String> existing_model_sets_name = new HashSet<>();
            for (String path : existing_model_sets) {
                existing_model_sets_name.add(Paths.get(path).getFileName().toString());
            }
            for (File f : new File(dirname).listFiles()) {
                if (f.isDirectory()) {
                    RemoveDuplicateFiles(f.listFiles(), existing_model_sets_name.contains(f.getName()));
                }
            }
        }

        try {
            // write to readme github query used
            readmefile.write("Removed " + numDuplicateFiles + " duplicate files" + "\n");
        } catch (Exception e) {
            e.printStackTrace();
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
            if (GatherFromGithub() == 1) {
                System.out.println("Failed to gather models from github");
                return;
            }
        }

        // Gather from existing models-sets
        if (gatherFromExistingModelSets) {
            if (GatherFromExistingModelSets() == 1) {
                System.out.println("Failed to gather models from existing model sets");
                return;
            }
        }

        // Remove non-Alloy files, Alloy util/library models and duplicate models if options set
        if (CleanUpFiles() == 1) {
            System.out.println("Failed to remove not needed files");
            return;
        }

        try {
            String outputCount = "Total " + alsFileNames.size() + " .als " +
                    "files.\n";

            if (gatherFromGithub && gatherFromExistingModelSets) {
                outputCount += alsFileNames.size() - numFilesFromExisting + " " +
                        ".als files drawn from " + num_git_repos + " github " +
                        "repos and " + numFilesFromExisting + " .als files " +
                        "drawn from " + existing_model_sets.length + " " +
                        "existing model set directories.";
            } else if (gatherFromGithub) {
                outputCount += alsFileNames.size() - numFilesFromExisting + " " +
                        ".als files drawn from " + num_git_repos + " github " +
                        "repos.";
            } else if (gatherFromExistingModelSets) {
                outputCount += numFilesFromExisting + " .als files " +
                        "drawn from " + existing_model_sets.length + " " +
                        "existing model set directories.";
            }
            readmefile.write(outputCount + "\n");

            readmefile.close();
        } catch (Exception e) {
            System.out.println("Failed to close readme file");
            return;
        }
        return;
    }
}
