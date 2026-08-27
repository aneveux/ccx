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
