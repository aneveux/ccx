package me.neveux.ccx.cli;

import me.neveux.ccx.service.CcxConfigService;
import me.neveux.ccx.service.ClaudeMarketplaceRegistryService;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.SettingsService;
import me.neveux.ccx.service.SkillsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConfigCommand")
class ConfigCommandTest {

    @TempDir
    Path ccxConfigDir;

    private CommandLine commandLine;
    private final ByteArrayOutputStream outputCapture = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        var settingsService = new SettingsService();
        var factory = new TestCommandFactory(
            settingsService, null, new PluginMapService(),
            new ClaudeMdService(),
            new SkillsService(settingsService, new ClaudeMarketplaceRegistryService()),
            null, null,
            new CcxConfigService(ccxConfigDir)
        );
        commandLine = new CommandLine(new CcxCommand(), factory);
        commandLine.setOut(new PrintWriter(outputCapture, true));
        commandLine.setErr(new PrintWriter(outputCapture, true));
    }

    private int run(String... args) {
        outputCapture.reset();
        return commandLine.execute(args);
    }

    private String output() {
        return outputCapture.toString();
    }

    @Nested
    @DisplayName("config claudemd new")
    class ClaudeMdNew {

        @Test
        @DisplayName("should create fragment file with template content")
        void createsFragmentFile() throws Exception {
            int exit = run("config", "claudemd", "new", "java-debug");

            assertThat(exit)
                .as("config claudemd new must exit 0")
                .isZero();
            assertThat(output())
                .as("output must print Created message")
                .contains("Created:");
            var fragFile = ccxConfigDir.resolve("claudemd/java-debug.md");
            assertThat(fragFile)
                .as("fragment file must be created")
                .exists();
            assertThat(Files.readString(fragFile))
                .as("fragment file must contain template content")
                .contains("# java-debug");
        }

        @Test
        @DisplayName("should reject invalid name with uppercase")
        void rejectsInvalidName() {
            int exit = run("config", "claudemd", "new", "BadName");

            assertThat(exit)
                .as("config claudemd new with invalid name must exit 1")
                .isEqualTo(1);
            assertThat(output())
                .as("output must mention invalid name")
                .contains("invalid fragment name");
        }

        @Test
        @DisplayName("should reject duplicate fragment name")
        void rejectsDuplicate() {
            run("config", "claudemd", "new", "java-debug");

            int exit = run("config", "claudemd", "new", "java-debug");

            assertThat(exit)
                .as("second creation of same fragment must exit 1")
                .isEqualTo(1);
            assertThat(output())
                .as("output must mention already exists")
                .contains("already exists");
        }
    }
}
