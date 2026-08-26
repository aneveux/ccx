// Wave 3 — Commands
package me.neveux.ccx.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import me.neveux.ccx.model.MarketplaceSource;
import me.neveux.ccx.model.Settings;
import me.neveux.ccx.service.CcxConfigService;
import me.neveux.ccx.service.CcxContextService;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DoctorCommand")
class DoctorCommandTest {

    @TempDir
    Path tempDir;

    /**
     * Stand-in for the user home so plugin verification never reads the developer's real
     * {@code ~/.claude/settings.json} — its marketplaces would otherwise decide the verdicts.
     */
    @TempDir
    Path isolatedGlobalDir;

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

    private RunResult run(String... args) {
        var out = new ByteArrayOutputStream();
        commandLine.setOut(new PrintWriter(out, true));
        var exit = commandLine.execute(args);
        return new RunResult(exit, out.toString());
    }

    @Test
    @DisplayName("should exit 0 and show [OK] for all checks on a valid project")
    void doctorOnValidProject_exitsZero() throws Exception {
        var settings = new Settings().withEnabledPlugins(Map.of("java@claude-java-stack", true));
        settingsService.write(settings, tempDir);

        var result = run("doctor", "--project-dir", tempDir.toString(),
                "--global-dir", isolatedGlobalDir.toString());

        assertThat(result.exitCode())
            .as("doctor on valid project must exit 0")
            .isZero();
        assertThat(result.output())
            .as("doctor must show [OK] for all checks (non-TTY fallback contract)")
            .contains("[OK]");
    }

    @Test
    @DisplayName("should thread an ALLGOOD quip into the all-clear sign-off")
    void doctorAllClear_carriesAllGoodQuip() throws Exception {
        var settings = new Settings().withEnabledPlugins(Map.of("java@claude-java-stack", true));
        settingsService.write(settings, tempDir);

        var result = run("doctor", "--project-dir", tempDir.toString(),
                "--global-dir", isolatedGlobalDir.toString());

        assertThat(result.exitCode())
            .as("a healthy project must exit 0")
            .isZero();
        assertThat(result.output())
            .as("the all-clear sign-off must carry one of the ALLGOOD quips")
            .containsAnyOf(me.neveux.ccx.tui.Quips.ALLGOOD);
    }

    @Test
    @DisplayName("should exit non-zero and show [ERROR] when settings has a missing stack ref")
    void doctorWithMissingStackRef_exitsNonZero() throws Exception {
        // Reference a non-existent stack file
        var settings = new Settings().withEnabledPlugins(Map.of("java@claude-java-stack", true));
        settingsService.write(settings, tempDir);

        // Write .claude/CLAUDE.md with a broken @-reference to a non-existent file
        var claudeMd = tempDir.resolve(".claude/CLAUDE.md");
        Files.createDirectories(claudeMd.getParent());
        Files.writeString(claudeMd,
            "# Project\n<!-- ccx:begin -->\n@/nonexistent/path/to/stack.md\n<!-- ccx:end -->\n");

        var result = run("doctor", "--project-dir", tempDir.toString(),
                "--global-dir", isolatedGlobalDir.toString());

        assertThat(result.exitCode())
            .as("doctor with missing stack ref must exit non-zero")
            .isNotZero();
        assertThat(result.output())
            .as("doctor must report [ERROR] for missing stack ref")
            .contains("[ERROR]");
    }

    @Test
    @DisplayName("should exit non-zero and show [ERROR] when settings.json is malformed JSON")
    void doctorWithMalformedSettingsJson_exitsNonZero() throws Exception {
        var claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(claudeDir.resolve("settings.json"), "{ this is not valid json }}}");

        var result = run("doctor", "--project-dir", tempDir.toString(),
                "--global-dir", isolatedGlobalDir.toString());

        assertThat(result.exitCode())
            .as("doctor with malformed settings.json must exit non-zero")
            .isNotZero();
        assertThat(result.output())
            .as("doctor must report [ERROR] for malformed JSON")
            .contains("[ERROR]");
        assertThat(result.output())
            .as("plugin/MCP/skill INFO checks must be suppressed when settings is unreadable")
            .doesNotContain("plugin(s) enabled")
            .doesNotContain("MCP server")
            .doesNotContain("skill override");
    }

    @Test
    @DisplayName("should show per-plugin lines (not bare count) when plugins are enabled")
    void doctorWithPlugins_showsPluginCount() throws Exception {
        var settings = new Settings().withEnabledPlugins(Map.of("java@claude-java-stack", true, "quarkus@claude-java-stack", true));
        settingsService.write(settings, tempDir);

        var result = run("doctor", "--project-dir", tempDir.toString(),
                "--global-dir", isolatedGlobalDir.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
            .as("output must not use the old bare count format")
            .doesNotContain("plugin(s) enabled");
        assertThat(result.output())
            .as("each plugin must appear by name in the output")
            .contains("java@claude-java-stack")
            .contains("quarkus@claude-java-stack");
    }

    @Test
    @DisplayName("should show [INFO] for MCP servers when configured")
    void doctorWithMcpServers_showsMcpCount() throws Exception {
        var settings = new Settings();
        var server = new me.neveux.ccx.model.McpServer("http", "https://example.com", null, null);
        settings = settings.withMcpServers(Map.of("my-server", server));
        settingsService.write(settings, tempDir);

        var result = run("doctor", "--project-dir", tempDir.toString(),
                "--global-dir", isolatedGlobalDir.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("[INFO]");
        assertThat(result.output()).contains("MCP");
    }

    @Test
    @DisplayName("should show [INFO] for no plugins when enabledPlugins is null")
    void doctorWithNoSettings_showsZeroPlugins() throws Exception {
        var result = run("doctor", "--project-dir", tempDir.toString(),
                "--global-dir", isolatedGlobalDir.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("[INFO]");
        assertThat(result.output()).contains("0 plugin(s)");
    }

    @Nested
    @DisplayName("when global settings are present")
    class WhenGlobalSettingsPresent {

        @TempDir
        Path globalDir;

        @Test
        @DisplayName("should report [INFO] global plugins count from --global-dir")
        void doctor_reportsGlobalPluginCount() throws Exception {
            var globalSettings = new Settings().withEnabledPlugins(Map.of("plugin-one", true, "plugin-two", true));
            settingsService.write(globalSettings, globalDir);

            var result = run("doctor",
                "--project-dir", tempDir.toString(),
                "--global-dir", globalDir.toString());

            assertThat(result.exitCode()).isZero();
            assertThat(result.output())
                .as("doctor must report global plugin count from --global-dir")
                .contains("global plugins: 2");
        }
    }

    @Nested
    @DisplayName("when certifying plugins")
    class WhenCertifyingPlugins {

        @Test
        @DisplayName("should show unverified line when no marketplace is configured")
        void doctorWithNoMarketplace_showsPluginNameUnverified() throws Exception {
            var settings = new Settings().withEnabledPlugins(Map.of("java@claude-java-stack", true));
            settingsService.write(settings, tempDir);

            var result = run("doctor", "--project-dir", tempDir.toString(),
                "--global-dir", isolatedGlobalDir.toString());

            assertThat(result.exitCode()).isZero();
            assertThat(result.output())
                .as("output must contain unverified line for the plugin")
                .contains("[INFO] plugin enabled: java@claude-java-stack (unverified");
            assertThat(result.output())
                .as("output must not use the old bare count format")
                .doesNotContain("plugin(s) enabled");
        }

        @Test
        @DisplayName("should show verified line when plugin is found in marketplace")
        void doctorWithMarketplace_showsVerifiedPlugin(@TempDir Path marketplaceDir) throws Exception {
            // Create a subdirectory named after the plugin's short name so buildMap() discovers it
            Files.createDirectories(marketplaceDir.resolve("java"));

            var source = new MarketplaceSource("claude-java-stack", marketplaceDir.toString(), null, null);

            var settings = new Settings()
                .withEnabledPlugins(Map.of("java@claude-java-stack", true))
                .withExtraKnownMarketplaces(List.of(source));
            settingsService.write(settings, tempDir);

            var result = run("doctor", "--project-dir", tempDir.toString(),
                "--global-dir", isolatedGlobalDir.toString());

            assertThat(result.exitCode()).isZero();
            assertThat(result.output())
                .as("output must contain verified line for the plugin")
                .contains("[OK] plugin enabled: java@claude-java-stack (verified)");
        }

        @Test
        @DisplayName("should show warning when plugin key is not found in marketplace")
        void doctorWithMarketplace_showsWarningForMissingPlugin(@TempDir Path marketplaceDir) throws Exception {
            // The marketplace dir exists but has no subdir matching "unknown-plugin"
            Files.createDirectories(marketplaceDir.resolve("other-plugin"));

            var source = new MarketplaceSource("my-marketplace", marketplaceDir.toString(), null, null);

            var settings = new Settings()
                .withEnabledPlugins(Map.of("unknown-plugin@my-marketplace", true))
                .withExtraKnownMarketplaces(List.of(source));
            settingsService.write(settings, tempDir);

            var result = run("doctor", "--project-dir", tempDir.toString(),
                "--global-dir", isolatedGlobalDir.toString());

            assertThat(result.exitCode()).isZero();
            assertThat(result.output())
                .as("output must warn when plugin is absent from marketplace")
                .contains("[WARNING] plugin enabled: unknown-plugin@my-marketplace (not found in marketplace)");
        }

        @Test
        @DisplayName("should show verified line when the marketplace is declared only in global settings")
        void doctorWithGlobalOnlyMarketplace_showsVerifiedPlugin(@TempDir Path marketplaceDir) throws Exception {
            Files.createDirectories(marketplaceDir.resolve("java"));

            var source = new MarketplaceSource("claude-java-stack", marketplaceDir.toString(), null, null);

            var globalSettings = new Settings().withExtraKnownMarketplaces(List.of(source));
            settingsService.write(globalSettings, isolatedGlobalDir);

            var settings = new Settings().withEnabledPlugins(Map.of("java@claude-java-stack", true));
            settingsService.write(settings, tempDir);

            var result = run("doctor", "--project-dir", tempDir.toString(),
                "--global-dir", isolatedGlobalDir.toString());

            assertThat(result.exitCode()).isZero();
            assertThat(result.output())
                .as("a marketplace declared at user scope must still verify the plugin")
                .contains("[OK] plugin enabled: java@claude-java-stack (verified)");
            assertThat(result.output())
                .as("global-scope marketplaces must not produce a not-found warning")
                .doesNotContain("(not found in marketplace)")
                .doesNotContain("(unverified");
        }

        @Test
        @DisplayName("should still warn when neither project nor global settings know the plugin")
        void doctorWithGlobalMarketplace_stillWarnsForMissingPlugin(@TempDir Path marketplaceDir) throws Exception {
            Files.createDirectories(marketplaceDir.resolve("other-plugin"));

            var source = new MarketplaceSource("my-marketplace", marketplaceDir.toString(), null, null);

            var globalSettings = new Settings().withExtraKnownMarketplaces(List.of(source));
            settingsService.write(globalSettings, isolatedGlobalDir);

            var settings = new Settings().withEnabledPlugins(Map.of("unknown-plugin@my-marketplace", true));
            settingsService.write(settings, tempDir);

            var result = run("doctor", "--project-dir", tempDir.toString(),
                "--global-dir", isolatedGlobalDir.toString());

            assertThat(result.output())
                .as("merging global sources must not mask a genuinely absent plugin")
                .contains("[WARNING] plugin enabled: unknown-plugin@my-marketplace (not found in marketplace)");
        }

        @Test
        @DisplayName("should show disabled line when plugin value is false")
        void doctorWithDisabledPlugin_showsDisabledLine() throws Exception {
            var settings = new Settings().withEnabledPlugins(Map.of("some-plugin", false));
            settingsService.write(settings, tempDir);

            var result = run("doctor", "--project-dir", tempDir.toString(),
                "--global-dir", isolatedGlobalDir.toString());

            assertThat(result.exitCode()).isZero();
            assertThat(result.output())
                .as("output must show disabled line for plugin with value=false")
                .contains("[INFO] plugin disabled: some-plugin");
        }
    }

    @Nested
    @DisplayName("when ccx config dir is present")
    class WhenCcxConfigDirPresent {

        @TempDir
        Path ccxConfigDir;

        @Test
        @DisplayName("should report [OK] with profile count when profiles directory exists")
        void doctor_reportsCcxConfigDirWithProfileCount() throws Exception {
            var profilesDir = ccxConfigDir.resolve("profiles/java");
            Files.createDirectories(profilesDir);
            Files.writeString(profilesDir.resolve("profile.yaml"),
                "metadata:\n  name: java\n  description: java\n");

            var factory = new TestCommandFactory(
                settingsService, new me.neveux.ccx.service.ProfileService(),
                new PluginMapService(), new ClaudeMdService(),
                new me.neveux.ccx.service.SkillsService(settingsService),
                null, null,
                new me.neveux.ccx.service.CcxConfigService(ccxConfigDir)
            );
            var cl = new CommandLine(new CcxCommand(), factory);
            var out = new java.io.ByteArrayOutputStream();
            cl.setOut(new PrintWriter(out, true));
            var exit = cl.execute("doctor", "--project-dir", tempDir.toString());

            assertThat(exit).isZero();
            assertThat(out.toString())
                .as("doctor must report ccx config dir with profile count")
                .contains("[OK] ccx config:")
                .contains("1 profile(s)");
        }

        @Test
        @DisplayName("should report [INFO] when ccx config dir does not exist")
        void doctor_reportsCcxConfigDirAbsent() {
            var absentDir = tempDir.resolve("absent-ccx-config");
            var factory = new TestCommandFactory(
                settingsService, new me.neveux.ccx.service.ProfileService(),
                new PluginMapService(), new ClaudeMdService(),
                new me.neveux.ccx.service.SkillsService(settingsService),
                null, null,
                new me.neveux.ccx.service.CcxConfigService(absentDir)
            );
            var cl = new CommandLine(new CcxCommand(), factory);
            var out = new java.io.ByteArrayOutputStream();
            cl.setOut(new PrintWriter(out, true));
            var exit = cl.execute("doctor", "--project-dir", tempDir.toString());

            assertThat(exit).isZero();
            assertThat(out.toString())
                .as("doctor must report [INFO] when ccx config dir is absent")
                .contains("[INFO] ccx config directory not found:");
        }
    }

    // -----------------------------------------------------------------------
    // Plan 046 — .claudeignore check removed from doctor output
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Plan 046 — no .claudeignore check in doctor output")
    class NoDotClaudeIgnoreCheck {

        @Test
        @DisplayName("doctor output must not mention .claudeignore after migration to deny rules")
        void doctor_doesNotMentionClaudeIgnore() {
            var result = run("doctor", "--project-dir", tempDir.toString(),
                "--global-dir", isolatedGlobalDir.toString());

            assertThat(result.output())
                .as("doctor must not check for .claudeignore — "
                    + "ignore patterns now live in permissions.deny, not a separate file")
                .doesNotContain(".claudeignore");
        }
    }

    @Nested
    @DisplayName("when a profile has a missing dependency")
    class WhenBrokenDependency {

        @TempDir
        Path ccxConfigDir;

        @Test
        @DisplayName("should report [WARNING] and exit non-zero when an enabled profile's dependency is disabled")
        void doctor_reportsBrokenDependency() throws Exception {
            // java (dependency) and java-functional (dependent) profiles on disk
            var javaDir = ccxConfigDir.resolve("profiles/java");
            Files.createDirectories(javaDir);
            Files.writeString(javaDir.resolve("profile.yaml"),
                "metadata:\n  name: java\nplugins:\n  enable:\n    - java@claude-java-stack\n");
            var jfDir = ccxConfigDir.resolve("profiles/java-functional");
            Files.createDirectories(jfDir);
            Files.writeString(jfDir.resolve("profile.yaml"),
                "metadata:\n  name: java-functional\nextends:\n  - java\n"
                + "plugins:\n  enable:\n    - java-functional@claude-java-stack\n");

            // Context records java-functional enabled but NOT java → broken dependency
            var ccxDir = tempDir.resolve(".ccx");
            Files.createDirectories(ccxDir);
            Files.writeString(ccxDir.resolve("context.json"), """
                {"version":"1","enabledProfiles":{"java-functional":{"enabledAt":"2024-01-15T10:00:00Z",
                "snapshot":{"plugins":[],"rules":[],"permissionsAllow":[],"permissionsDeny":[]}}}}
                """);

            var yamlMapper = new ObjectMapper(new YAMLFactory());
            yamlMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            yamlMapper.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
            var profileService = new ProfileService(yamlMapper, new CcxConfigService(ccxConfigDir));
            var factory = new TestCommandFactory(
                settingsService, profileService, new PluginMapService(), new ClaudeMdService(),
                new me.neveux.ccx.service.SkillsService(settingsService),
                null, new CcxContextService(),
                new CcxConfigService(ccxConfigDir));
            var cl = new CommandLine(new CcxCommand(), factory);
            var out = new ByteArrayOutputStream();
            cl.setOut(new PrintWriter(out, true));
            var exit = cl.execute("doctor", "--project-dir", tempDir.toString());

            assertThat(out.toString())
                .as("doctor must warn about the missing dependency java for java-functional")
                .contains("[WARNING]")
                .contains("java-functional")
                .contains("java");
            assertThat(exit)
                .as("a broken dependency must make doctor exit non-zero")
                .isNotZero();
        }
    }

    private record RunResult(int exitCode, String output) {}
}
