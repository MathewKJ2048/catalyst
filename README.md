# Alloy Models

Alloy models is a repository of Alloy models and contains scripts for scraping Alloy models and filtering these sets for particular characteristics.

This repo is for internal Watform member use only.  The downloaded Alloy files from github may not have permissions for us to make them publicly available to others.

[NAD: we need to think about what that means for making our artefacts public for papers.]

## Using the Model Sets

The directory "models-sets" contains sets of Alloy models (sometimes within the directory hierarchy they came with) created for various purposes.  Each directory is dated by the date is was created.  The README.md file within with model set directory tells you what filters (programmatically or manually) to create this model set.

## Building a Model Set Using the Scripts

### Program the filters in the Script

See src/main/java/alloymodeltools/alloymodeltools.java and choose the sources and filters you want to use to create a new model set.

### Method 1: Running the Script directly using gradle

You can run the script using "./gradlew run"

### Method 2: Separate Compilation and Run

#### Building the Script

In the root directory, run "./gradlew build".   This results in "build/libs/allow-model-sets-all.jar" (which is a fat jar built by shadowJar).

#### Running the Scripts

Run "java -jar build/libs/allow-model-sets-all.jar"

This will output diagnostic information and create your new model set in "models-sets/date-time/".  Within that directory there will be an initial README.md file that shows how the set was created programmatically.  You may wish to do more filtering and add to the README.md the effects of your own (manual or otherwise) filters.

