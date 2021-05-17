# Alloy Models

Alloy models is a repository of Alloy models and contains scripts for scraping
Alloy models and filtering these sets for particular characteristics.

This repo is for internal Watform member use only. The downloaded Alloy files
from github may not have permissions for us to make them publicly available to
others.

[NAD: we need to think about what that means for making our artefacts public for papers.]

## Using the Model Sets

The directory "models-sets" contains sets of Alloy models (sometimes within the
directory hierarchy they came with) created for various purposes. Each directory
is dated by the date is was created. The README.md file within with model set
directory tells you what filters (programmatically or manually) to create this
model set.

## Building a Model Set Using the Scripts

### Setup

Put the alloy jar (
org.alloytools.alloy/org.alloytools.alloy.dist/target/org.alloytools.alloy.dist.jar)
in /libs subdirectory. You can either clone the org.alloytools.alloy repo,
follow its instructions to compile it to get a jar or download a jar directly
from its releases.

### Program the filters in the Script

See src/main/java/alloymodeltools/alloymodeltools.java and choose the sources
and filters you want to use to create a new model set.

### Method 1: Running the Script directly using gradle

You can run the script using "./gradlew run"

### Method 2: Separate Compilation and Run

#### Building the Script

In the root directory, run "./gradlew build". This results in "
build/libs/allow-model-sets-all.jar" (which is a fat jar built by shadowJar).

#### Running the Scripts

Run "java -jar build/libs/alloy-model-sets-all.jar"

This will output diagnostic information and create your new model set in "
models-sets/date-time/". Within that directory there will be an initial
README.md file that shows how the set was created programmatically. You may wish
to do more filtering and add to the README.md the effects of your own (manual or
otherwise) filters.

## Design Notes

* we keep the hierarchy of the path when building new repositories from old ones
  because path information may be valuable
* for duplicates, we compare the file name and file size, and then randomly keep
  one of the duplicates

### How the command scopes are set in Alloy

Each Command has an overall scope that is -1 or higher and a list of "
CommandScope".  
Each sig may have a CommandScope that includes its position, signature, isExact,
startingScope, endingScope and increment.

The scopes are determined as follows:

* "run x": every topsig is scoped to <= 3 elements.
* "run x for N": every topsig is scoped to <= N elements.
* "run x for N but N1 SIG1, N2 SIG2...":  
  Every sig following "but" is constrained explicitly.  
  Any topsig that is  
  a) not listed, and  
  b) its scope is not derived otherwise  
  will be scoped to have <= N elements.
* "run x for N1 SIG1, N2 SIG2..."  
  Every sig following "but" is constrained explicitly.  
  Any topsig that is  
  a) not listed, and <br>
  b) its scope is not derived otherwise <br>
  we will give an error message.

Please see "
org.alloytools.alloy/org.alloytools.alloy.core/src/main/java/edu/mit/csail/sdg/translator/ScopeComputer.java"
for the exact rules for deriving the missing scopes.

#### Examples:

1. run … → overall = -1, scope = []
2. run … for 2 → overall = 2, scope = []
3. run … for 2 A, 3 B → overall = -1, scope
   = [A: startingScope=endingScope=2, isExact=false; B:startingScope=endingScope=3, isExact=false]
4. run … for exactly 3 B → overall = -1, scope
   = [B: startingScope=endingScope=3, isExact=true]
5. run … for 3 but 1 A → overall = 3, scope
   = [A: startingScope=endingScope=1, isExact=false]
6. In a model that has “one sig A1,A2 extends A”, if we set a scope of 5 for A1.
   We will get a syntax error when executing the command!
