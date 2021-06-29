package alloymodelsettools;

import org.apache.commons.codec.binary.Base32;
import org.apache.commons.codec.digest.DigestUtils;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedSearchIterable;
import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;

import java.io.*;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.apache.commons.codec.digest.MessageDigestAlgorithms.SHA_256;

public class util {

    public static String getSystemInfo() {
        SystemInfo systemInfo = new SystemInfo();
        HardwareAbstractionLayer hardware = systemInfo.getHardware();

        String s = "This program is running on the following platform:\n";
        s += "Operating System: " + systemInfo.getOperatingSystem().toString() + " : " + System.getProperty("os.arch") + "\n";
        s += "CPU: " + hardware.getProcessor().getProcessorIdentifier().getName() + "\n";
        s += "Memory: " + hardware.getMemory().toString() + "\n";
        return s;
    }

    static String sha256(String s) {
        return new Base32().encodeAsString(new DigestUtils(SHA_256).digest(s));
    }

    static String sha256_n(String s, int n) {
        return sha256(s).substring(0, n).toLowerCase();
    }

    static String sha256_32(String s) {
        return sha256_n(s, 32);
    }

    static Integer GatherFromGithub(int num_git_repos, Logger logger, String dirname, FileWriter readmefile) {
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

    static Integer DownloadPlatinumFromGoogleDrive(Logger logger, String dirname, FileWriter readmefile) {
        // Get download url from sending GET request to the following url
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", "curl https://drive.google.com/uc\\?export\\=download\\&id\\=1jmfxcB9pr2kBBub-Su43AQsAmleUUAeB");
        pb.directory(new File(dirname));
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            String downloadURL = "";
            while ((line = reader.readLine()) != null) {
                logger.info(line);
                // Grab download url
                if (line.contains("The document has moved")) {
                    downloadURL = line.split("\"")[1];
                }
            }
            reader.close();
            int exitVal = process.waitFor();
            // If we didn't get downloadURL, return 1
            if (exitVal != 0 | downloadURL.isEmpty()) {
                logger.warning("Abnormal Behaviour! Something bad happened when requesting Platinum download url.");
                return 1;
            }

            // Download the Platinum.zip file and unzip it
            pb = new ProcessBuilder("bash", "-c", "curl " + downloadURL + " --output Platinum.zip\nunzip Platinum.zip");
            pb.directory(new File(dirname));
            pb.redirectErrorStream(true);
            process = pb.start();
            reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            while ((line = reader.readLine()) != null) {
                logger.info(line);
            }
            reader.close();
            exitVal = process.waitFor();
            if (exitVal != 0) {
                logger.warning("Abnormal Behaviour! Something bad happened when downloading and unzipping Platinum.zip");
                return 1;
            }

            // write to readme github query used
            readmefile.write("Downloaded Platinum model sets from Google drive.\n");
            return 0;
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            return 1;
        }
    }

    static Integer GatherFromExistingModelSets(String[] existing_model_sets, Logger logger, String dirname, FileWriter readmefile) {
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
                        logger.warning("Abnormal Behaviour! Something bad happened when copying files.");
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

    static Integer RemoveNonAlloyFiles(Logger logger, String dirname) {
        String cleanUpCommands = "rm -rf .*\n" + // remove hidden files
                "find . -mindepth 2 -depth -type f ! -name '*.als' -delete\n" +
                "find . -type d -empty -delete\n";

        ProcessBuilder pb = new ProcessBuilder("bash", "-c", cleanUpCommands);
        pb.directory(new File(dirname));
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            int exitVal = process.waitFor();
            if (exitVal != 0) {
                logger.warning("Abnormal Behaviour! Something bad happened when removing non-alloy files.");
                return 1;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            return 1;
        }
        return 0;
    }
}


