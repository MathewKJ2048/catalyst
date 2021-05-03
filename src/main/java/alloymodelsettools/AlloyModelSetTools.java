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

import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.BufferedReader;

import java.time.format.DateTimeFormatter;  
import java.time.LocalDateTime;    

import org.kohsuke.github.*;
import static org.apache.commons.codec.digest.MessageDigestAlgorithms.SHA_256;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.codec.binary.Base32;

// We should probably have a constant for the max number of repos to clone?
// and how many models to find

public class AlloyModelSetTools {

	static FileWriter readmefile;
	static String dirname;
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
	   		dirname = "model-sets/"+ dtf.format(now); 
	   		String readmefilename =  dirname + "/" + "README.md";

			File f = new File(readmefilename);
			f.getParentFile().mkdirs();
	    	readmefile = new FileWriter(readmefilename);	    	
	    	readmefile.write("Model set created: "+dirname+"\n");
	    	return 0;
	    } catch (Exception e) {
	    	System.out.println("An error occurred creating the model set directory and/or README.md file");
	    	return 1;
	    }
    }

    static Integer GatherFromGithub() {

	// Elias - explain the github query
    	String query = "language:alloy NOT ableton NOT midi NOT music NOT mIRC -repo:AlloyTools/models";
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
        	
        	PagedSearchIterable<GHRepository> repos = gh.searchRepositories().q(query).list();

        	System.out.println("Search query: " + query);
        	Integer numresults = repos.getTotalCount();
        	System.out.println("# of results: " + numresults);
        	
	        for (GHRepository repo : repos) {
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
	        }
            // Now we have a giant string of "git clone"s
	        // NAD: can we use Java's process builder to actually do the git cloning?
            // NAD: the following was copied from the web and isn't yet working
/*        	ProcessBuilder pb = new ProcessBuilder(str);
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
                	System.out.println("Abnormal Behaviour! Something bad happened.");
            	}
        	} catch (Exception e) {
        		e.printStackTrace();
        	}
*/
	        // write to readme github query used
	        readmefile.write("Scraped from "+numresults+" github repos with query: "+query+"\n");
	        return 0;
	    } catch (Exception e) {
	    	e.printStackTrace();
	    	return 1;
	    }
    }

    static Integer GatherFromExistingModelSets(String[] dirs) {
    	// Do not duplicate anything already in model-set directory
    	// write to readme which directories gathered from
	// Elias 
    	return 0;
    }


    public static void main(String[] args) {

    	// Start a new model set directory
    	// expects to be run in root of alloy-models-sets directory
    	if (CreateModelSetDir() == 1) { 
    		return;
    	}
    	// Gather from github
    	if (GatherFromGithub() == 1) {
    		System.out.println("Failed to gather models from github");
    		return;
    	}
    	// Gather from existing models-sets
    	/*
    	existing_model_sets_to_use = []
    	if GatherFromExistingModelSets(existing_model_sets) == 1 {
    		System.out.println("Failed to gather models from existing model sets")
    		return;
    	}
    	*/
    	try {
    		readmefile.close();
    	} catch (Exception e) {
    		System.out.println("Failed to close readme file");
            return;
        }
    	return;
    }
}
