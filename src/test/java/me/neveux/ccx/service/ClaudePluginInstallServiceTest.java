package me.neveux.ccx.service;

import me.neveux.ccx.util.ProcessRunner.ProcessResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClaudePluginInstallService")
class ClaudePluginInstallServiceTest {

    /** Records the argv and working directory instead of spawning a real Claude Code CLI. */
    private static class RecordingService extends ClaudePluginInstallService {
        final List<List<String>> invocations = new ArrayList<>();
        Path capturedWorkingDir;
        ProcessResult result = new ProcessResult(0, "Installed plugin");
        IOException failure;

        private final Path cli;

        RecordingService(Path cli) {
            this.cli = cli;
        }

        @Override
        protected Optional<Path> claudeCli() {
            return Optional.ofNullable(cli);
        }

        @Override
        protected ProcessResult runInstall(List<String> args, Path workingDir) throws IOException {
            invocations.add(List.copyOf(args));
            capturedWorkingDir = workingDir;
            if (failure != null) throw failure;
            return result;
        }
    }

    private static RecordingService serviceWithCli(Path tempDir) throws IOException {
        var cli = tempDir.resolve("claude");
        Files.createFile(cli);
        return new RecordingService(cli);
    }

    @Nested
    @DisplayName("install()")
    class Install {

        @Test
        @DisplayName("runs the Claude Code CLI with project scope in the project directory")
        void validName_runsCliWithProjectScope(@TempDir Path tempDir) throws Exception {
            var project = Files.createDirectories(tempDir.resolve("project"));
            var service = serviceWithCli(tempDir);

            var result = service.install("slack@claude-plugins-official", project);

            assertThat(result.status())
                .as("a successful CLI run must report the plugin as installed")
                .isEqualTo(ClaudePluginInstallService.Status.INSTALLED);
            assertThat(service.invocations)
                .as("exactly one install invocation, with project scope and the qualified name")
                .singleElement()
                .satisfies(args -> assertThat(args.subList(1, args.size()))
                    .containsExactly("plugin", "install", "slack@claude-plugins-official", "--scope", "project"));
            assertThat(service.capturedWorkingDir)
                .as("cwd must be the project so Claude Code sees its extraKnownMarketplaces")
                .isEqualTo(project);
        }

        @Test
        @DisplayName("reports FAILED with the CLI output when the install exits non-zero")
        void nonZeroExit_reportsFailedWithOutput(@TempDir Path tempDir) throws Exception {
            var project = Files.createDirectories(tempDir.resolve("project"));
            var service = serviceWithCli(tempDir);
            service.result = new ProcessResult(1, "Plugin not found in any marketplace");

            var result = service.install("ghost@mkt", project);

            assertThat(result.status())
                .as("a non-zero exit is an install failure")
                .isEqualTo(ClaudePluginInstallService.Status.FAILED);
            assertThat(result.message())
                .as("the CLI output is the only diagnostic the user gets — it must be carried through")
                .contains("Plugin not found in any marketplace");
        }

        @Test
        @DisplayName("reports FAILED rather than throwing when the process cannot be run")
        void processError_reportsFailed(@TempDir Path tempDir) throws Exception {
            var project = Files.createDirectories(tempDir.resolve("project"));
            var service = serviceWithCli(tempDir);
            service.failure = new IOException("command timed out after 180s");

            var result = service.install("slack@claude-plugins-official", project);

            assertThat(result.status())
                .as("install must never throw at its call sites")
                .isEqualTo(ClaudePluginInstallService.Status.FAILED);
            assertThat(result.message())
                .as("the underlying process error must reach the user")
                .contains("timed out");
        }

        @Test
        @DisplayName("refuses a malformed plugin identifier without spawning anything")
        void malformedName_spawnsNothing(@TempDir Path tempDir) throws Exception {
            var project = Files.createDirectories(tempDir.resolve("project"));
            var service = serviceWithCli(tempDir);

            // A leading `-` on either half would reach `claude` as a flag rather than an argument,
            // so the allowlist requires both halves to start alphanumerically.
            for (var name : List.of("slack; rm -rf /", "no-marketplace", "a@b@c", "--scope", "", "  ",
                                    "--version@mkt", "slack@--flag", "-rf@mkt")) {
                var result = service.install(name, project);
                assertThat(result.status())
                    .as("'%s' is not a valid <plugin>@<marketplace> identifier", name)
                    .isEqualTo(ClaudePluginInstallService.Status.INVALID_NAME);
            }
            assertThat(service.invocations)
                .as("argument validation must happen before any process is spawned")
                .isEmpty();
        }

        @Test
        @DisplayName("refuses a null plugin identifier")
        void nullName_isInvalid(@TempDir Path tempDir) throws Exception {
            var service = serviceWithCli(tempDir);

            assertThat(service.install(null, tempDir).status())
                .as("a null identifier must not reach the CLI")
                .isEqualTo(ClaudePluginInstallService.Status.INVALID_NAME);
            assertThat(service.invocations).isEmpty();
        }

        @Test
        @DisplayName("reports CLI_NOT_FOUND with the manual command when no claude binary is available")
        void noCli_reportsCliNotFound(@TempDir Path tempDir) {
            var service = new RecordingService(null);

            var result = service.install("slack@claude-plugins-official", tempDir);

            assertThat(result.status())
                .as("without the Claude Code CLI ccx cannot install anything")
                .isEqualTo(ClaudePluginInstallService.Status.CLI_NOT_FOUND);
            assertThat(result.message())
                .as("the user must be told exactly what to run by hand")
                .contains("claude plugin install slack@claude-plugins-official --scope project");
            assertThat(service.invocations).isEmpty();
        }
    }

    @Nested
    @DisplayName("installAll()")
    class InstallAll {

        /** Fails exactly the plugins whose identifier is in {@code failing}, succeeds on the rest. */
        private static class SelectivelyFailingService extends RecordingService {
            private final List<String> failing;

            SelectivelyFailingService(Path cli, List<String> failing) {
                super(cli);
                this.failing = failing;
            }

            @Override
            protected ProcessResult runInstall(List<String> args, Path workingDir) throws IOException {
                super.runInstall(args, workingDir);
                return failing.stream().anyMatch(args::contains)
                    ? new ProcessResult(1, "no such plugin")
                    : new ProcessResult(0, "Installed plugin");
            }
        }

        private static SelectivelyFailingService serviceFailing(Path tempDir, String... failing) throws IOException {
            var cli = tempDir.resolve("claude");
            Files.createFile(cli);
            return new SelectivelyFailingService(cli, List.of(failing));
        }

        @Test
        @DisplayName("attempts every plugin even after one fails, keyed in attempt order")
        void oneFailure_doesNotStopTheOthers(@TempDir Path tempDir) throws Exception {
            var service = serviceFailing(tempDir, "ghost@mkt");
            var project = Files.createDirectory(tempDir.resolve("project"));

            var results = service.installAll(List.of("first@mkt", "ghost@mkt", "third@mkt"), project);

            assertThat(results.keySet())
                .as("a marketplace that cannot resolve one plugin says nothing about the rest")
                .containsExactly("first@mkt", "ghost@mkt", "third@mkt");
            assertThat(results.get("first@mkt").status()).isEqualTo(ClaudePluginInstallService.Status.INSTALLED);
            assertThat(results.get("ghost@mkt").status()).isEqualTo(ClaudePluginInstallService.Status.FAILED);
            assertThat(results.get("third@mkt").status()).isEqualTo(ClaudePluginInstallService.Status.INSTALLED);
            assertThat(service.invocations)
                .as("all three installs must have been attempted")
                .hasSize(3);
        }

        @Test
        @DisplayName("spawns nothing for a null or empty list")
        void nothingToInstall_spawnsNothing(@TempDir Path tempDir) throws Exception {
            var service = serviceWithCli(tempDir);

            assertThat(service.installAll(null, tempDir)).isEmpty();
            assertThat(service.installAll(List.of(), tempDir)).isEmpty();
            assertThat(service.invocations).isEmpty();
        }

        @Test
        @DisplayName("notifies the progress callback before each install, not after")
        void progressCallback_firesBeforeEachInstall(@TempDir Path tempDir) throws Exception {
            var trace = new ArrayList<String>();
            var cli = tempDir.resolve("claude");
            Files.createFile(cli);
            var service = new RecordingService(cli) {
                @Override
                protected ProcessResult runInstall(List<String> args, Path workingDir) throws IOException {
                    trace.add("install");
                    return super.runInstall(args, workingDir);
                }
            };

            service.installAll(List.of("first@mkt", "second@mkt"), tempDir, name -> trace.add("before:" + name));

            assertThat(trace)
                .as("a caller rendering progress needs the notification while the subprocess runs")
                .containsExactly("before:first@mkt", "install", "before:second@mkt", "install");
        }
    }

    /**
     * Drives the ADR-015 discovery ladder with the environment lookup, the {@code which} subprocess
     * and the home directory all replaced, so the outcome depends on the ladder rather than on
     * whether the machine running the suite has the Claude Code CLI installed.
     */
    private static class DiscoveringService extends ClaudePluginInstallService {
        String envOverride;
        String home;
        ProcessResult whichResult = new ProcessResult(1, "");
        int whichCalls;

        @Override
        protected String cliEnvOverride() {
            return envOverride;
        }

        @Override
        protected String homeDirectory() {
            return home;
        }

        @Override
        protected ProcessResult runDiscovery(List<String> args) {
            whichCalls++;
            return whichResult;
        }
    }

    @Nested
    @DisplayName("claudeCli() discovery ladder")
    class Discovery {

        @Test
        @DisplayName("an explicit override wins and PATH is never consulted")
        void envOverride_isUsedWithoutTouchingPath(@TempDir Path tempDir) throws Exception {
            var pinned = Files.createFile(tempDir.resolve("pinned-claude"));
            var service = new DiscoveringService();
            service.envOverride = pinned.toString();

            assertThat(service.claudeCli()).contains(pinned);
            assertThat(service.whichCalls)
                .as("a pinned binary must be used as-is — consulting PATH would make the pin advisory")
                .isZero();
        }

        @Test
        @DisplayName("an override that does not resolve yields empty and is NOT backfilled from PATH")
        void envOverrideMissing_doesNotFallBackToPath(@TempDir Path tempDir) {
            var onPath = tempDir.resolve("path-claude");
            var service = new DiscoveringService();
            service.envOverride = tempDir.resolve("nonexistent-claude").toString();
            service.whichResult = new ProcessResult(0, onPath + "\n");

            assertThat(service.claudeCli())
                .as("a wrong pin must fail visibly instead of silently installing with another binary")
                .isEmpty();
            assertThat(service.whichCalls).isZero();
        }

        @Test
        @DisplayName("with no override, the binary found on PATH is used")
        void noOverride_usesPathResult(@TempDir Path tempDir) throws Exception {
            var onPath = Files.createFile(tempDir.resolve("path-claude"));
            var service = new DiscoveringService();
            service.whichResult = new ProcessResult(0, onPath + "\n");

            assertThat(service.claudeCli()).contains(onPath);
        }

        @Test
        @DisplayName("a PATH hit that is a directory rather than a file is rejected")
        void pathResultThatIsADirectory_isRejected(@TempDir Path tempDir) {
            var service = new DiscoveringService();
            service.whichResult = new ProcessResult(0, tempDir + "\n");
            service.home = tempDir.resolve("no-home").toString();

            assertThat(service.claudeCli())
                .as("spawning a directory as an executable fails at install time with a worse message")
                .isEmpty();
        }

        @Test
        @DisplayName("with nothing on PATH, the two fallback locations are tried in order")
        void noPathResult_prefersLocalBinOverClaudeLocal(@TempDir Path homeDir) throws Exception {
            var localBin = homeDir.resolve(".local/bin/claude");
            var claudeLocal = homeDir.resolve(".claude/local/claude");
            Files.createDirectories(localBin.getParent());
            Files.createDirectories(claudeLocal.getParent());
            Files.createFile(localBin);
            Files.createFile(claudeLocal);

            var service = new DiscoveringService();
            service.home = homeDir.toString();

            assertThat(service.claudeCli())
                .as("~/.local/bin/claude is declared first, so it must win when both exist")
                .contains(localBin);
        }

        @Test
        @DisplayName("the second fallback location is used when the first is absent")
        void noPathResult_fallsBackToClaudeLocal(@TempDir Path homeDir) throws Exception {
            var claudeLocal = homeDir.resolve(".claude/local/claude");
            Files.createDirectories(claudeLocal.getParent());
            Files.createFile(claudeLocal);

            var service = new DiscoveringService();
            service.home = homeDir.toString();

            assertThat(service.claudeCli()).contains(claudeLocal);
        }

        @Test
        @DisplayName("no override, nothing on PATH and no fallback file yields empty")
        void nothingAnywhere_yieldsEmpty(@TempDir Path homeDir) {
            var service = new DiscoveringService();
            service.home = homeDir.toString();

            assertThat(service.claudeCli()).isEmpty();
        }

        @Test
        @DisplayName("discovery is memoized across calls")
        void repeatedCalls_discoverOnce(@TempDir Path tempDir) throws Exception {
            var onPath = Files.createFile(tempDir.resolve("path-claude"));
            var service = new DiscoveringService();
            service.whichResult = new ProcessResult(0, onPath + "\n");

            service.claudeCli();
            service.claudeCli();

            assertThat(service.whichCalls)
                .as("every install would otherwise pay for a `which` subprocess")
                .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("remediationCommand()")
    class RemediationCommand {

        @Test
        @DisplayName("reproduces the command Claude Code itself suggests")
        void matchesClaudeCodeSuggestion() {
            assertThat(new ClaudePluginInstallService().remediationCommand("slack@claude-plugins-official"))
                .as("staying identical to Claude Code's own remediation string keeps the advice copy-pasteable")
                .isEqualTo("claude plugin install slack@claude-plugins-official --scope project");
        }
    }
}
