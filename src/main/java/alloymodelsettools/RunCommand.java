package alloymodelsettools;

import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.ast.Module;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;

public class RunCommand {
    // Attempts to shut down an ExecutorService and wait 10s for tasks to
    // respond to being cancelled. Note there's no guarantee it shut downs the
    // ExecutorService.
    static void shutdownAndAwaitTermination(ExecutorService pool) {
        // shutdownNow() attempts to stop all actively executing tasks, halts
        // the processing of waiting tasks. But, there are no guarantees
        // beyond best-effort attempts to stop processing actively executing
        // tasks. For example, any task that fails to respond to interrupts
        // may never terminate.
        pool.shutdownNow();
        try {
            // Wait a while for tasks to respond to being cancelled
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                System.err.println("ExecutorService did not terminate");
            }
        } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            pool.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }

    // If the new overall scope is -1, leave the command unchanged.
    // Otherwise, set the overall scope, and drop all individual scopes and
    // exact scopes.
    public static Command changeOverallScope(Command cmd, int overall) {
        if (overall == -1) return cmd;
        return new Command(cmd.pos, cmd.nameExpr, cmd.label, cmd.check, overall,
                cmd.bitwidth, cmd.maxseq, cmd.minprefix, cmd.maxprefix,
                cmd.expects, new ArrayList<>(), cmd.additionalExactScopes,
                cmd.formula, cmd.parent);
    }

    // Takes in command line arguments: .als file path, whichCommand to be
    // executed (first, second, or ...) and the overall scope to be set to.
    public static void main(String[] args) {
        File file = new File(args[0]);
        int whichCommand = Integer.parseInt(args[1]);
        int overall = Integer.parseInt(args[2]);

        // Parse+typecheck the model
        System.out.println("=========== Parsing+Typechecking " + file.getPath() + " =============");
        try {
            Module world = CompUtil.parseEverything_fromFile(null, null, file.getPath());

            // Choose some default options for how you want to execute the commands
            A4Options options = new A4Options();

            options.solver = A4Options.SatSolver.SAT4J;
            Command command = world.getAllCommands().get(whichCommand);
            final Command newCommand = changeOverallScope(command, overall);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Long> handler = executor.submit(new Callable() {
                @Override
                public Long call() throws Exception {
                    // Execute the command
                    System.out.println("============ Command " + newCommand + ": ============");
                    long startTime = System.nanoTime();
                    A4Solution ans = TranslateAlloyToKodkod.execute_command(null, world.getAllReachableSigs(), newCommand, options);
                    // Print the outcome
                    // System.out.println(ans);
                    // If satisfiable...
                    if (ans.satisfiable()) {
                        System.out.println("Satisfiable?: SAT");
                    } else {
                        System.out.println("Satisfiable?: UNSAT");
                    }
                    long endTime = System.nanoTime();
                    return endTime - startTime;
                }
            });
            try {
                System.out.println("Execution time(ns): " + handler.get(Duration.ofSeconds(AlloyModelSetTools.higher_bound_of_time_range_in_seconds + 1).toMillis(), TimeUnit.MILLISECONDS));
                System.exit(0);
            } catch (TimeoutException e) {
                // Timeout
                System.out.println("TIMEOUT");
                shutdownAndAwaitTermination(executor);
                System.exit(1);
            } catch (Exception e) {
                // Exception thrown
                System.out.println("Something bad happened when executing command: " + newCommand);
                e.printStackTrace(System.out);
                shutdownAndAwaitTermination(executor);
                System.exit(2);
            }
            shutdownAndAwaitTermination(executor);
        } catch (Exception e) {
            // Exception thrown
            System.out.println("Something bad happened in the RunCommand process.");
            e.printStackTrace(System.out);
            System.exit(2);
        }
        // Should never reach here. This line is added here to ensure it always terminates.
        System.exit(3);
    }
}
