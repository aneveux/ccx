// Wave 1 — CLI scope/help regression guards [TDD baseline]
package me.neveux.ccx.cli;

import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.ProfileService;
import me.neveux.ccx.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 1 — parent-command help regression guard.
 *
 * <p>Each parent command ({@code plugins}, {@code profiles}, {@code skills}, {@code mcp},
 * {@code claude-md}, {@code config}) currently has a no-op {@code run()}. Picocli does NOT
 * auto-show usage when a Runnable parent is executed with no subcommand, so these commands
 * print nothing today. After Wave 1 makes {@code run()} print usage, the subcommand names
 * appear in the output.
 *
 * <p>Usage may be written to stdout OR stderr depending on picocli config, so both streams
 * are captured into a single buffer and the assertions run against the combined text.
 */
@DisplayName("Parent commands: running with no subcommand must show usage listing subcommands")
class ParentCommandHelpTest {

    private CommandLine commandLine;
    private final ByteArrayOutputStream combined = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        var settingsService = new SettingsService();
        var factory = new TestCommandFactory(
            settingsService, new ProfileService(), new PluginMapService(),
            new ClaudeMdService()
        );
        commandLine = new CommandLine(new CcxCommand(), factory);
        // Capture stdout AND stderr into one buffer so the test is robust to where picocli
        // writes usage.
        commandLine.setOut(new PrintWriter(combined, true));
        commandLine.setErr(new PrintWriter(combined, true));
    }

    private String runAndCapture(String... args) {
        combined.reset();
        commandLine.execute(args);
        return combined.toString();
    }

    @Nested
    @DisplayName("plugins")
    class Plugins {

        @Test
        @DisplayName("running 'plugins' with no subcommand should list its subcommands")
        void plugins_whenNoSubcommand_showsSubcommandNames() {
            var output = runAndCapture("plugins");

            assertThat(output)
                .as("plugins help must list its subcommands (list, enable)")
                .contains("list")
                .contains("enable");
        }
    }

    @Nested
    @DisplayName("profiles")
    class Profiles {

        @Test
        @DisplayName("running 'profiles' with no subcommand should list its subcommands")
        void profiles_whenNoSubcommand_showsSubcommandNames() {
            var output = runAndCapture("profiles");

            assertThat(output)
                .as("profiles help must list its subcommands (list, enable, disable)")
                .contains("list")
                .contains("enable")
                .contains("disable");
        }
    }

    @Nested
    @DisplayName("skills")
    class Skills {

        @Test
        @DisplayName("running 'skills' with no subcommand should list its subcommands")
        void skills_whenNoSubcommand_showsSubcommandNames() {
            var output = runAndCapture("skills");

            assertThat(output)
                .as("skills help must list its subcommands (list, hide)")
                .contains("list")
                .contains("hide");
        }
    }

    @Nested
    @DisplayName("mcp")
    class Mcp {

        @Test
        @DisplayName("running 'mcp' with no subcommand should list its subcommands")
        void mcp_whenNoSubcommand_showsSubcommandNames() {
            var output = runAndCapture("mcp");

            assertThat(output)
                .as("mcp help must list its subcommands (list, enable)")
                .contains("list")
                .contains("enable");
        }
    }

    @Nested
    @DisplayName("claude-md")
    class ClaudeMd {

        @Test
        @DisplayName("running 'claude-md' with no subcommand should list its subcommands")
        void claudeMd_whenNoSubcommand_showsSubcommandNames() {
            var output = runAndCapture("claude-md");

            assertThat(output)
                .as("claude-md help must list its subcommands (list, add, remove)")
                .contains("list")
                .contains("add")
                .contains("remove");
        }
    }

    @Nested
    @DisplayName("config")
    class Config {

        @Test
        @DisplayName("running 'config' with no subcommand should list its subcommands")
        void config_whenNoSubcommand_showsSubcommandNames() {
            var output = runAndCapture("config");

            assertThat(output)
                .as("config help must list its claudemd subcommand")
                .contains("claudemd");
        }
    }
}
