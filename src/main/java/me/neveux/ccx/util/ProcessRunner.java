package me.neveux.ccx.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs an external command in a subprocess and captures its combined output.
 *
 * <p>Hardened against the three ways a naive {@link ProcessBuilder} call goes wrong: arguments
 * are always passed as a list so no shell ever interprets them, stdout is drained on a virtual
 * thread so a chatty child cannot fill the pipe buffer and deadlock, and the wait is bounded by
 * a timeout after which the process is destroyed forcibly.
 *
 * <p>A non-zero exit is returned to the caller, not thrown — callers differ on whether failure is
 * fatal. Callers are responsible for validating the command and its arguments against an
 * allowlist before calling in.
 */
public final class ProcessRunner {

    private ProcessRunner() {}

    /**
     * Outcome of a subprocess invocation.
     *
     * @param exitCode process exit status; {@code 0} on success
     * @param output   combined stdout and stderr, never null
     */
    public record ProcessResult(int exitCode, String output) {

        /**
         * @return true when the process exited successfully
         */
        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    /**
     * Runs {@code args} and returns its exit status and combined output.
     *
     * @param args           command and arguments; the first element is the executable
     * @param workingDir     directory to run in, or null to inherit the current one
     * @param env            extra environment variables to set, or null for none
     * @param timeoutSeconds how long to wait before destroying the process
     * @return the exit status and combined output
     * @throws IOException if the process cannot be started, times out, or the wait is interrupted
     */
    public static ProcessResult run(List<String> args, Path workingDir, Map<String, String> env, int timeoutSeconds)
        throws IOException {

        var processBuilder = new ProcessBuilder(args);
        if (workingDir != null) {
            processBuilder.directory(workingDir.toFile());
        }
        if (env != null) {
            processBuilder.environment().putAll(env);
        }
        processBuilder.redirectErrorStream(true);

        var process = processBuilder.start();
        // Drain stdout in a virtual thread so it never fills the pipe buffer and deadlocks.
        // readAllBytes() completes when the process exits or is destroyed.
        var outputRef = new String[1];
        var drainThread = Thread.ofVirtual().start(() -> {
            try (var in = process.getInputStream()) {
                outputRef[0] = new String(in.readAllBytes());
            } catch (IOException ignored) {
                outputRef[0] = "";
            }
        });
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                drainThread.join(1000);
                throw new IOException("command timed out after " + timeoutSeconds + "s: " + args);
            }
            drainThread.join(5000);
            return new ProcessResult(process.exitValue(), outputRef[0] != null ? outputRef[0] : "");
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("command interrupted: " + args, e);
        }
    }
}
