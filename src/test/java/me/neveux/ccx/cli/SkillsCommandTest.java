// Wave 2 — verb-taxonomy normalization (enable / disable / hide / show / reset / list)
package me.neveux.ccx.cli;

import me.neveux.ccx.model.MarketplaceSource;
import me.neveux.ccx.model.Settings;
import me.neveux.ccx.service.ClaudeMarketplaceRegistryService;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.ProfileService;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SkillsCommand")
class SkillsCommandTest {

    @TempDir
    Path projectDir;

    @TempDir
    Path globalDir;

    private SettingsService settingsService;
    private SkillsService skillsService;
    private CommandLine commandLine;
    private final ByteArrayOutputStream outputCapture = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        settingsService = new SettingsService();
        skillsService = new SkillsService(settingsService, isolatedRegistry());

        var factory = new TestCommandFactory(
            settingsService, new ProfileService(), new PluginMapService(),
            new ClaudeMdService(), skillsService
        );
        commandLine = new CommandLine(new CcxCommand(), factory);
        commandLine.setOut(new PrintWriter(outputCapture, true));
        commandLine.setErr(new PrintWriter(outputCapture, true));
    }

    /** A registry pointed at an absent file, so skill discovery never sees the real {@code ~/.claude}. */
    private ClaudeMarketplaceRegistryService isolatedRegistry() {
        return new ClaudeMarketplaceRegistryService() {
            @Override
            protected Path registryFile() {
                return globalDir.resolve("plugins/known_marketplaces.json");
            }
        };
    }

    private int run(String... args) {
        outputCapture.reset();
        return commandLine.execute(args);
    }

    private String output() {
        return outputCapture.toString();
    }

    /**
     * Creates the expected directory structure for a skill:
     * {@code <marketplaceDir>/<pluginName>/skills/<skillName>/SKILL.md}
     * and wires the marketplace source into project settings.
     */
    private void createSkill(String marketplaceKey, String pluginName, String skillName) throws Exception {
        var marketplaceDir = projectDir.resolve("marketplace-" + marketplaceKey);
        var skillDir = marketplaceDir.resolve(pluginName).resolve("skills").resolve(skillName);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "# " + skillName);

        var source = new MarketplaceSource(marketplaceKey, marketplaceDir.toString(), null, null);

        var settings = settingsService.read(projectDir).withExtraKnownMarketplaces(List.of(source));
        settingsService.write(settings, projectDir);
    }

    @Nested
    @DisplayName("skills list")
    class List_ {

        @Test
        @DisplayName("should show the active (💚) emoji for a skill with no override")
        void list_showsGreenForSkillWithNoOverride() throws Exception {
            createSkill("mkt", "java", "java");

            run("skills", "list",
                "--project-dir", projectDir.toString(),
                "--global-dir", globalDir.toString());

            assertThat(output())
                .as("skill with no override must show the active emoji, not a geometric glyph")
                .contains("💚")
                .doesNotContain("●")
                .contains("java:java");
        }

        @Test
        @DisplayName("should show the hidden (💛) emoji for a skill overridden to user-invocable-only")
        void list_showsEyeForUserInvocableOnly() throws Exception {
            createSkill("mkt", "java", "java");
            skillsService.setOverride("java:java", "user-invocable-only", projectDir);

            run("skills", "list",
                "--project-dir", projectDir.toString(),
                "--global-dir", globalDir.toString());

            assertThat(output())
                .as("user-invocable-only skill must show the hidden emoji, not a geometric glyph")
                .contains("💛")
                .doesNotContain("◐")
                .contains("java:java");
        }

        @Test
        @DisplayName("should show the off (🤍) emoji for a skill overridden to off")
        void list_showsStopForOff() throws Exception {
            createSkill("mkt", "java", "java");
            skillsService.setOverride("java:java", "off", projectDir);

            run("skills", "list",
                "--project-dir", projectDir.toString(),
                "--global-dir", globalDir.toString());

            assertThat(output())
                .as("off skill must show the off emoji, not a geometric glyph")
                .contains("🤍")
                .doesNotContain("○")
                .contains("java:java");
        }
    }

    @Nested
    @DisplayName("skills hide — security")
    class HideSecurity {

        @Test
        @DisplayName("should reject --project-dir pointing outside allowed directories and report error")
        void hide_rejectsProjectDirOutsideAllowedDirs() {
            run("skills", "hide", "java:java", "--project-dir", "/etc");

            assertThat(output())
                .as("hide with --project-dir outside allowed dirs must report an error")
                .containsIgnoringCase("outside allowed");
        }

        @Test
        @DisplayName("should exit 1 when --project-dir is outside allowed directories")
        void hide_exitsOne_whenProjectDirOutsideAllowedDirs() {
            int exitCode = run("skills", "hide", "java:java", "--project-dir", "/etc");

            assertThat(exitCode)
                .as("hide must exit 1 (not 0) when the path-security guard rejects --project-dir")
                .isEqualTo(1);
            assertThat(output())
                .as("hide rejection must report the security error")
                .containsIgnoringCase("outside allowed");
        }

        @Test
        @DisplayName("should exit 0 on a valid --project-dir")
        void hide_exitsZero_whenProjectDirValid() {
            int exitCode = run("skills", "hide", "java:java", "--project-dir", projectDir.toString());

            assertThat(exitCode)
                .as("hide must exit 0 on success")
                .isEqualTo(0);
            assertThat(settingsService.read(projectDir).skillOverrides())
                .as("hide success must have written the override")
                .containsEntry("java:java", "user-invocable-only");
        }
    }

    @Nested
    @DisplayName("skills enable — security")
    class EnableSecurity {

        @Test
        @DisplayName("should reject --project-dir pointing outside allowed directories and report error")
        void enable_rejectsProjectDirOutsideAllowedDirs() {
            run("skills", "enable", "java:java", "--project-dir", "/etc");

            assertThat(output())
                .as("enable with --project-dir outside allowed dirs must report an error")
                .containsIgnoringCase("outside allowed");
        }

        @Test
        @DisplayName("should exit 1 when --project-dir is outside allowed directories")
        void enable_exitsOne_whenProjectDirOutsideAllowedDirs() {
            int exitCode = run("skills", "enable", "java:java", "--project-dir", "/etc");

            assertThat(exitCode)
                .as("enable must exit 1 (not 0) when the path-security guard rejects --project-dir")
                .isEqualTo(1);
            assertThat(output())
                .as("enable rejection must report the security error")
                .containsIgnoringCase("outside allowed");
        }

        @Test
        @DisplayName("should exit 0 on a valid --project-dir")
        void enable_exitsZero_whenProjectDirValid() {
            settingsService.write(
                buildSettingsWithOverride("java:java", "off"),
                projectDir
            );

            int exitCode = run("skills", "enable", "java:java", "--project-dir", projectDir.toString());

            assertThat(exitCode)
                .as("enable must exit 0 on success")
                .isEqualTo(0);
            assertThat(settingsService.read(projectDir).skillOverrides())
                .as("enable success must have removed the override")
                .isNullOrEmpty();
        }
    }

    @Nested
    @DisplayName("skills show — security")
    class ShowSecurity {

        @Test
        @DisplayName("should reject --project-dir pointing outside allowed directories and report error")
        void show_rejectsProjectDirOutsideAllowedDirs() {
            run("skills", "show", "java:java", "--project-dir", "/etc");

            assertThat(output())
                .as("show with --project-dir outside allowed dirs must report an error")
                .containsIgnoringCase("outside allowed");
        }
    }

    @Nested
    @DisplayName("skills hide")
    class Hide {

        @Test
        @DisplayName("should write user-invocable-only override to project settings")
        void hide_writesUserInvocableOnlyOverride() {
            run("skills", "hide", "java:java",
                "--project-dir", projectDir.toString());

            var written = settingsService.read(projectDir);
            assertThat(written.skillOverrides())
                .as("hide must set user-invocable-only override for the skill")
                .containsEntry("java:java", "user-invocable-only");
        }
    }

    @Nested
    @DisplayName("skills enable")
    class Enable {

        @Test
        @DisplayName("should remove an existing override (returns skill to auto)")
        void enable_removesOverride() {
            settingsService.write(
                buildSettingsWithOverride("java:java", "user-invocable-only"),
                projectDir
            );

            run("skills", "enable", "java:java",
                "--project-dir", projectDir.toString());

            var written = settingsService.read(projectDir);
            assertThat(written.skillOverrides())
                .as("enable must remove the override key from skillOverrides")
                .isNullOrEmpty();
        }

        @Test
        @DisplayName("should acknowledge the enabled skill in output")
        void enable_acknowledgesSkill() {
            settingsService.write(
                buildSettingsWithOverride("java:java", "off"),
                projectDir
            );

            run("skills", "enable", "java:java",
                "--project-dir", projectDir.toString());

            assertThat(settingsService.read(projectDir).skillOverrides())
                .as("enable must remove the override")
                .isNullOrEmpty();
            assertThat(output())
                .as("enable must acknowledge the skill it enabled")
                .contains("java:java");
        }
    }

    @Nested
    @DisplayName("skills disable")
    class Disable {

        @Test
        @DisplayName("should write off override to project settings")
        void disable_writesOffOverride() {
            run("skills", "disable", "java:java",
                "--project-dir", projectDir.toString());

            var written = settingsService.read(projectDir);
            assertThat(written.skillOverrides())
                .as("disable must set off override for the skill")
                .containsEntry("java:java", "off");
        }

        @Test
        @DisplayName("should exit 1 when --project-dir is outside allowed directories")
        void disable_exitsOne_whenProjectDirOutsideAllowedDirs() {
            int exitCode = run("skills", "disable", "java:java", "--project-dir", "/etc");

            assertThat(exitCode)
                .as("disable must exit 1 (not 0) when the path-security guard rejects --project-dir")
                .isEqualTo(1);
            assertThat(output())
                .as("disable rejection must report the security error")
                .containsIgnoringCase("outside allowed");
        }

        @Test
        @DisplayName("should exit 0 on a valid --project-dir")
        void disable_exitsZero_whenProjectDirValid() {
            int exitCode = run("skills", "disable", "java:java", "--project-dir", projectDir.toString());

            assertThat(exitCode)
                .as("disable must exit 0 on success")
                .isEqualTo(0);
            assertThat(settingsService.read(projectDir).skillOverrides())
                .as("disable success must have written the off override")
                .containsEntry("java:java", "off");
        }
    }

    @Nested
    @DisplayName("skills show")
    class Show {

        @Test
        @DisplayName("should print the current status without mutating settings")
        void show_printsStatusAndDoesNotMutate() {
            settingsService.write(
                buildSettingsWithOverride("java:java", "off"),
                projectDir
            );

            run("skills", "show", "java:java",
                "--project-dir", projectDir.toString(),
                "--global-dir", globalDir.toString());

            assertThat(output())
                .as("show must print the resolved status of the skill")
                .contains("off");
            assertThat(settingsService.read(projectDir).skillOverrides())
                .as("show is a read — it must NOT mutate the override")
                .containsEntry("java:java", "off");
        }

        @Test
        @DisplayName("should print auto for a skill with no override")
        void show_printsAutoWhenNoOverride() {
            run("skills", "show", "java:java",
                "--project-dir", projectDir.toString(),
                "--global-dir", globalDir.toString());

            assertThat(output())
                .as("show must print 'auto' when no override exists")
                .contains("auto");
        }
    }

    @Nested
    @DisplayName("skills reset")
    class Reset {

        @Test
        @DisplayName("should remove all overrides and print each removed skill ID in output")
        void reset_removesAllOverridesAndPrintsNames() {
            settingsService.write(
                buildSettingsWithOverrides(Map.of(
                    "java:java", "off",
                    "java:java-testing", "user-invocable-only"
                )),
                projectDir
            );

            run("skills", "reset",
                "--project-dir", projectDir.toString());

            var written = settingsService.read(projectDir);
            assertThat(written.skillOverrides())
                .as("reset must clear all skillOverrides")
                .isNullOrEmpty();

            assertThat(output())
                .as("reset must print the removed skill IDs")
                .contains("java:java")
                .contains("java:java-testing");
        }

        @Test
        @DisplayName("should exit 1 when --project-dir is outside allowed directories")
        void reset_exitsOne_whenProjectDirOutsideAllowedDirs() {
            int exitCode = run("skills", "reset", "--project-dir", "/etc");

            assertThat(exitCode)
                .as("reset must exit 1 (not 0) when the path-security guard rejects --project-dir")
                .isEqualTo(1);
            assertThat(output())
                .as("reset rejection must report the security error")
                .containsIgnoringCase("outside allowed");
        }

        @Test
        @DisplayName("should exit 0 on a valid --project-dir")
        void reset_exitsZero_whenProjectDirValid() {
            settingsService.write(
                buildSettingsWithOverride("java:java", "off"),
                projectDir
            );

            int exitCode = run("skills", "reset", "--project-dir", projectDir.toString());

            assertThat(exitCode)
                .as("reset must exit 0 on success")
                .isEqualTo(0);
            assertThat(settingsService.read(projectDir).skillOverrides())
                .as("reset success must have cleared all overrides")
                .isNullOrEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Plan 040 Item 27 — ConfigWriteException propagation
    // RED until SkillsCommand.DisableCommand.call() catches ConfigWriteException
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("disable — ConfigWriteException propagation")
    class DisableConfigWriteException {

        /**
         * Hand-rolled SettingsService subclass that throws ConfigWriteException on write().
         * Mockito is not available on the test classpath.
         */
        static class FailingSettingsService extends SettingsService {
            @Override
            public void write(me.neveux.ccx.model.Settings settings, java.nio.file.Path projectRoot) {
                throw new me.neveux.ccx.service.ConfigWriteException(
                    "disk full", new RuntimeException("simulated disk full"));
            }
        }

        @Test
        @DisplayName("exits 1 and prints the ConfigWriteException message when disk write fails")
        void disableCommand_diskFull_exits1WithMessage() {
            var failingSettings = new FailingSettingsService();
            var failingSkillsService = new SkillsService(failingSettings, new ClaudeMarketplaceRegistryService());

            var factory = new TestCommandFactory(
                failingSettings, new ProfileService(), new PluginMapService(),
                new ClaudeMdService(), failingSkillsService
            );
            var out = new ByteArrayOutputStream();
            var cli = new CommandLine(new CcxCommand(), factory);
            cli.setOut(new PrintWriter(out, true));
            cli.setErr(new PrintWriter(out, true));

            int exitCode = cli.execute("skills", "disable", "java:java",
                "--project-dir", projectDir.toString());

            assertThat(exitCode)
                .as("disable must exit 1 when ConfigWriteException is thrown during write")
                .isEqualTo(1);
            assertThat(out.toString())
                .as("error output must contain the exception message or 'Error' keyword")
                .containsAnyOf("disk full", "Error", "error");
        }
    }

    @Nested
    @DisplayName("removed verbs")
    class RemovedVerbs {

        @Test
        @DisplayName("`skills restore` must be an unknown subcommand (exit code 2)")
        void restore_isUnknownSubcommand() {
            int exitCode = run("skills", "restore", "java:java",
                "--project-dir", projectDir.toString());

            assertThat(exitCode)
                .as("`skills restore` must no longer exist — picocli returns 2 for unknown subcommand")
                .isNotZero();
            assertThat(output())
                .as("picocli must report 'restore' as an unmatched argument")
                .containsIgnoringCase("Unmatched argument")
                .contains("restore");
        }

        @Test
        @DisplayName("`skills off` must be an unknown subcommand (exit code 2)")
        void off_isUnknownSubcommand() {
            int exitCode = run("skills", "off", "java:java",
                "--project-dir", projectDir.toString());

            assertThat(exitCode)
                .as("`skills off` must no longer exist — picocli returns 2 for unknown subcommand")
                .isNotZero();
            assertThat(output())
                .as("picocli must report 'off' as an unmatched argument")
                .containsIgnoringCase("Unmatched argument")
                .contains("off");
        }
    }

    private Settings buildSettingsWithOverride(String skillId, String mode) {
        var settings = new Settings().withSkillOverrides(new java.util.HashMap<>(Map.of(skillId, mode)));
        return settings;
    }

    private Settings buildSettingsWithOverrides(Map<String, String> overrides) {
        var settings = new Settings().withSkillOverrides(new java.util.HashMap<>(overrides));
        return settings;
    }
}
