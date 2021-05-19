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
* we perform binary search to look for an overall scope that make the command
  execution time falls in the desired range. We drop all individual scopes and
  exact scopes when doing binary search.

### How the command scopes are set in Alloy

Each Command has an overall scope that is 0 or higher, and defaults to -1 if no
overall scope is set.   
Each Command has a list of "CommandScope". A CommandScope has fields position,
signature, isExact, startingScope, endingScope and increment. A signature may or
may not have a corresponding CommandScope.

If you specify scopes for some signatures, then those signatures will each has
its corresponding command scope in the list of command scopes. Both of starting
scope and ending scope will be set to the number you specified. (Except for
growing signatures whose command will be skipped entirely in our tool.) If you
have "exactly" scope for some signature, then their command scope will have
isExact=true. If you didn't put any specific constraint on the scope of some
signatures, then they won't have any corresponding command scope. It's possible
that the list of command scopes is empty. You can look at examples below for
more information.

#### Rules for deriving the scopes in a command

In Alloy, the scopes in a command are derived as follows:

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
6. one sig A1,A2 extends A  
   run ... for 5 A1 → We will get a syntax error when executing the command!

### What if parsing an .als file/executing an command throws exceptions

In our tool, we define 5 possible outcomes for running a command in an Alloy
model

* SUCCESS: It executes successfully with execution time falls in the desired
  time range.
* TOOSHORT: It executes successfully but the execution time is shorter than the
  lower bound.
* TIMEOUT: It times out for the higher bound.
* EXCEPTION: It throws an exception when executing the command.
* UNKNOWN: Unknown error has occurred. It should never reach here.

#### CompUtil.parseEverything_fromFile

* throws an exception if importing files are not present

#### TranslateAlloyToKodkod.execute_command

* throws a syntax error "Sig "this/A1" has the multiplicity of "one", so its
  scope must be 1, and cannot be 5" for
  ```   
  one sig A1,A2 extends A  
  run ... for 5 A1 
  ```
  
