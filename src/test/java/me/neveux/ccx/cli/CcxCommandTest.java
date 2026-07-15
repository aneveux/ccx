package me.neveux.ccx.cli;

import me.neveux.ccx.model.Settings;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.ProfileService;
import me.neveux.ccx.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CcxCommand — version + bare banner")
class CcxCommandTest {

    @TempDir
    Path tempDir;

    private SettingsService settingsService;
    private CommandLine commandLine;

    @BeforeEach
    void setUp() {
        settingsService = new SettingsService();
        var factory = new TestCommandFactory(
            settingsService, new ProfileService(), new PluginMapService(),
            new ClaudeMdService()
        );
        commandLine = new CommandLine(new CcxCommand(), factory);
    }

    private record RunResult(int exitCode, String output) {}

    private RunResult run(String... args) {
        var out = new ByteArrayOutputStream();
        commandLine.setOut(new PrintWriter(out, true));
        var exit = commandLine.execute(args);
        return new RunResult(exit, out.toString());
    }

    @Nested
    @DisplayName("ccx --version")
    class Version {

        @Test
        @DisplayName("should print the application version, not an empty string")
        void version_printsResolvedVersion() {
            var result = run("--version");

            assertThat(result.exitCode())
                .as("--version must exit 0")
                .isZero();
            assertThat(result.output())
                .as("--version must surface a resolved semver-shaped application version")
                .containsPattern("ccx \\d+\\.\\d+\\.\\d+");
        }
    }

    @Nested
    @DisplayName("bare ccx banner")
    class Banner {

        @Test
        @DisplayName("should print the 🌿 signature banner instead of stock picocli help")
        void bare_printsSignatureBanner() {
            var result = run("--project-dir", tempDir.toString());

            assertThat(result.exitCode())
                .as("bare ccx must exit 0")
                .isZero();
            assertThat(result.output())
                .as("bare ccx must carry the 🌿 signature mark and the tool name")
                .contains("🌿")
                .doesNotContain("◉")
                .contains("ccx");
        }

        @Test
        @DisplayName("should nudge onboarding when nothing is configured")
        void bare_emptyProject_showsOnboardingNudge() {
            var result = run("--project-dir", tempDir.toString());

            assertThat(result.output())
                .as("an unconfigured project must be nudged toward profiles new")
                .contains("profiles new");
        }

        @Test
        @DisplayName("should report counts when plugins are enabled")
        void bare_withPlugins_showsCounts() throws Exception {
            var settings = new Settings().withEnabledPlugins(Map.of("java@claude-java-stack", true));
            settingsService.write(settings, tempDir);

            var result = run("--project-dir", tempDir.toString());

            assertThat(result.output())
                .as("a configured project must report how many plugins are enabled here")
                .contains("1 plugin");
        }
    }
}
