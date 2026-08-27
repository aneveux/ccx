package me.neveux.ccx.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the three failure modes {@link ProcessRunner} claims to be hardened against — an
 * unbounded wait, a full pipe buffer, and a lost interrupt — plus the documented decision to
 * return a non-zero exit rather than throw on it.
 *
 * <p>Linux-only commands ({@code sleep}, {@code /bin/sh}, {@code seq}) are deliberate: ccx ships a
 * single linux/x86_64 native binary, and its only subprocess is the Claude Code CLI.
 */
@DisplayName("ProcessRunner")
class ProcessRunnerTest {

    private static final int GENEROUS_TIMEOUT_SECONDS = 30;

    @Test
    @DisplayName("a process outliving its timeout is destroyed and reported, not waited on")
    void run_whenProcessExceedsTimeout_throwsAndReturnsPromptly() {
        var startedAt = System.nanoTime();

        assertThatThrownBy(() -> ProcessRunner.run(List.of("sleep", "30"), null, null, 1))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("timed out after 1s");

        var elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(elapsedMillis)
            .as("the timeout must destroy the child and bound the drain join, not wait for the "
                + "process to finish on its own (30s here)")
            .isLessThan(10_000);
    }

    @Test
    @DisplayName("output larger than a pipe buffer comes back whole instead of deadlocking")
    void run_withOutputExceedingPipeBuffer_returnsEveryLine() throws IOException {
        // ~290 KB, far past the 64 KB pipe buffer an undrained child would block on.
        var result = ProcessRunner.run(List.of("/bin/sh", "-c", "seq 1 50000"), null, null,
            GENEROUS_TIMEOUT_SECONDS);

        assertThat(result.exitCode()).as("seq must succeed").isZero();
        assertThat(result.output().lines().count())
            .as("every line the child wrote must be captured — a truncated count means the drain "
                + "raced the process exit")
            .isEqualTo(50_000);
        assertThat(result.output()).as("the last line must be present, not cut off").endsWith("50000\n");
    }

    @Test
    @DisplayName("a non-zero exit is returned to the caller rather than thrown")
    void run_withFailingCommand_returnsExitCode() throws IOException {
        var result = ProcessRunner.run(List.of("/bin/sh", "-c", "echo nope >&2; exit 3"), null, null,
            GENEROUS_TIMEOUT_SECONDS);

        assertThat(result.exitCode())
            .as("callers decide whether failure is fatal, so the status must survive intact")
            .isEqualTo(3);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.output())
            .as("stderr is redirected into the captured output")
            .contains("nope");
    }

    @Test
    @DisplayName("an interrupted wait rethrows as IOException and leaves the interrupt flag set")
    void run_whenWaitInterrupted_restoresInterruptFlag() throws InterruptedException {
        var thrown = new AtomicReference<Throwable>();
        var interruptStillSet = new AtomicBoolean();
        var entered = new CountDownLatch(1);

        var worker = new Thread(() -> {
            entered.countDown();
            try {
                ProcessRunner.run(List.of("sleep", "30"), null, null, GENEROUS_TIMEOUT_SECONDS);
            } catch (Throwable t) {
                thrown.set(t);
                interruptStillSet.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.start();
        entered.await();
        // Give the worker time to reach waitFor; interrupting before that would be a different path.
        Thread.sleep(300);
        worker.interrupt();
        worker.join(15_000);

        assertThat(thrown.get())
            .as("an interrupted wait must surface as an IOException naming the command")
            .isInstanceOf(IOException.class);
        assertThat(thrown.get()).hasMessageContaining("command interrupted");
        assertThat(interruptStillSet)
            .as("the interrupt must be restored so a shutting-down caller (the TUI install thread) "
                + "still sees it")
            .isTrue();
    }
}
