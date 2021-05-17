package alloymodelsettools;

import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.ast.Module;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.*;

public class RunCommandsInOneFile {
    static void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdownNow();
        try {
            // Wait a while for tasks to respond to being cancelled
            if (!pool.isTerminated() && !pool.awaitTermination(10, TimeUnit.SECONDS)) {
                System.err.println("ExecutorService did not terminate in 10s");
                pool.shutdownNow();
                if (!pool.isTerminated() && !pool.awaitTermination(60, TimeUnit.SECONDS)) {
                    System.err.println("ExecutorService did not terminate in 60s");
                }
            }
        } catch (InterruptedException ie) {
            System.err.println(ie);
            // (Re-)Cancel if current thread also interrupted
            pool.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        File file = new File(args[0]);
        String dirname = args[1];
        // Parse+typecheck the model
        System.out.println("=========== Parsing+Typechecking " + file.getPath() + " =============");
        try {
            Module world = CompUtil.parseEverything_fromFile(null, null, file.getPath());

            // Choose some default options for how you want to execute the commands
            A4Options options = new A4Options();

            options.solver = A4Options.SatSolver.SAT4J;

            for (int i = 0; i < world.getAllCommands().size(); i++) {
                Command command = world.getAllCommands().get(i);
                ExecutorService executor = Executors.newSingleThreadExecutor();
                Future<A4Solution> handler = executor.submit(new Callable() {
                    @Override
                    public A4Solution call() throws Exception {
                        // Execute the command
                        System.out.println("============ Command " + command + ": ============");
                        return TranslateAlloyToKodkod.execute_command(null, world.getAllReachableSigs(), command, options);
                    }
                });
                A4Solution ans;
                try {
                    ans = handler.get(Duration.ofSeconds(AlloyModelSetTools.maximum_time_to_run_a_command_in_seconds).toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    System.out.println("TIMEOUT");
                    shutdownAndAwaitTermination(executor);
                    continue;
                } catch (Exception e) {
                    System.out.println(e);
                    shutdownAndAwaitTermination(executor);
                    continue;
                }
                shutdownAndAwaitTermination(executor);
                // Print the outcome
                System.out.println(ans);
                // If satisfiable...
                if (ans.satisfiable()) {
                    System.out.println("SAT");
                } else {
                    System.out.println("UNSAT");
                }

                // If it is the last command
                if (i == world.getAllCommands().size() - 1) {
                    Path source = file.toPath();
                    Path target = Path.of(file.getPath().replace(dirname, dirname + (ans.satisfiable() ? "-SAT" : "-UNSAT")));
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.exit(0);
    }
}
