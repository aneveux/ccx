package me.neveux.ccx.tui;

import me.neveux.ccx.model.McpServer;
import me.neveux.ccx.model.Settings;
import me.neveux.ccx.service.CcxConfigService;
import me.neveux.ccx.service.CcxContextService;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.HomeIsolatedServices;
import me.neveux.ccx.service.ProfileApplyService;
import me.neveux.ccx.service.ProfileLifecycleService;
import me.neveux.ccx.service.ProfileService;
import me.neveux.ccx.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TuiActions")
class TuiActionsTest {

    @TempDir
    Path projectDir;

    private SettingsService settingsService;
    private ClaudeMdService claudeMdService;
    private TuiDataService tuiDataService;
    private TuiActions actions;

    @BeforeEach
    void setUp() throws Exception {
        settingsService = new SettingsService();
        claudeMdService = new ClaudeMdService();
        tuiDataService = new TuiDataService(settingsService, claudeMdService,
            new com.fasterxml.jackson.databind.ObjectMapper());
        actions = new TuiActions(settingsService, claudeMdService, tuiDataService,
            new ProfileLifecycleService());
        Files.createDirectories(projectDir.resolve(".claude"));
    }

    @Nested
    @DisplayName("togglePlugin()")
    class TogglePlugin {

        @Test
        @DisplayName("should enable a plugin that is not yet in settings")
        void togglePlugin_absentPlugin_enablesIt() {
            var snap = actions.togglePlugin("java@stack", projectDir);

            assertThat(snap.plugins())
                .as("plugin must appear as enabled after toggle")
                .anyMatch(p -> p.qualifiedName().equals("java@stack"));
            var written = settingsService.read(projectDir);
            assertThat(written.enabledPlugins())
                .as("settings.json must contain the newly enabled plugin")
                .containsKey("java@stack");
        }

        @Test
        @DisplayName("should disable a plugin that is already enabled")
        void togglePlugin_presentPlugin_disablesIt() {
            var settings = new Settings().withEnabledPlugins(new LinkedHashMap<>(Map.of("java@stack", true)));
            settingsService.write(settings, projectDir);

            actions.togglePlugin("java@stack", projectDir);

            var written = settingsService.read(projectDir);
            assertThat(written.enabledPlugins())
                .as("plugin must be removed from settings after toggle-off")
                .doesNotContainKey("java@stack");
        }

        @Test
        @DisplayName("should return a fresh snapshot reflecting the newly enabled plugin")
        void togglePlugin_returnsUpdatedSnapshot() {
            var snap = actions.togglePlugin("java@stack", projectDir);

            assertThat(snap.plugins())
                .as("returned snapshot must contain a PROJECT_ENABLED entry for the just-enabled plugin")
                .anyMatch(p -> p.qualifiedName().equals("java@stack")
                    && p.status() == TuiSnapshot.PluginStatus.PROJECT_ENABLED);
        }

        @Test
        @DisplayName("should reject toggling a globally-enabled plugin and set lastError")
        void togglePlugin_globallyEnabled_setsErrorAndLeavesSettingsUnchanged() throws Exception {
            var globalRoot = projectDir.resolve("global-home");
            Files.createDirectories(globalRoot.resolve(".claude"));
            var globalSettings = new Settings().withEnabledPlugins(new LinkedHashMap<>(Map.of("java@stack", true)));
            settingsService.write(globalSettings, globalRoot);

            actions.togglePlugin("java@stack", projectDir, globalRoot);

            var err = actions.consumeLastError();
            assertThat(err)
                .as("error message must indicate global plugin cannot be modified")
                .isEqualTo("Cannot modify global plugin configuration");
            var written = settingsService.read(projectDir);
            assertThat(written.enabledPlugins())
                .as("project settings must not be modified when plugin is globally enabled")
                .isNull();
        }

        // Plan 038 Issue 2 — the PLUGINS Enter handler in CcxApp must, when consumeLastError()
        // is non-null, surface `Quips.pick(IMPOSSIBLE, err.hashCode()) + "  " + err` instead of
        // the "toggled:" success line (mirrors handleProfileAction). CcxApp's handler is not
        // headlessly testable (2-arg togglePlugin reads the real ~/.claude global settings, and
        // the CcxApp ctor starts a background fetch thread requiring real clone services), so the
        // footer-surfacing itself is gated to the manual TTY check. This test pins the exact
        // message the handler must build from the guard's error, which is the strongest unit
        // assertion available. Compile-RED until Quips.IMPOSSIBLE exists.
        @Test
        @DisplayName("the impossible-toggle footer message embeds the error and is not a success line")
        void globalPluginToggle_footerMessage_surfacesError() throws Exception {
            var globalRoot = projectDir.resolve("global-home");
            Files.createDirectories(globalRoot.resolve(".claude"));
            var globalSettings = new Settings().withEnabledPlugins(new LinkedHashMap<>(Map.of("java@stack", true)));
            settingsService.write(globalSettings, globalRoot);

            actions.togglePlugin("java@stack", projectDir, globalRoot);
            var err = actions.consumeLastError();

            var footerMessage = Quips.pick(Quips.IMPOSSIBLE, err.hashCode()) + "  " + err;

            assertThat(footerMessage)
                .as("the surfaced footer line must contain the guard error, not a success toggle")
                .contains("Cannot modify global plugin configuration")
                .doesNotContain("toggled:");
        }
    }


    @Nested
    @DisplayName("toggleMcp()")
    class ToggleMcp {

        @Test
        @DisplayName("should remove MCP server when it is present in settings")
        void toggleMcp_presentServer_removesIt() {
            var server = new McpServer("http", "https://mcp.example.com", null, null);
            var settings = new Settings().withMcpServers(new LinkedHashMap<>(Map.of("my-server", server)));
            settingsService.write(settings, projectDir);

            var snap = actions.toggleMcp("my-server", projectDir);

            var written = settingsService.read(projectDir);
            assertThat(written.mcpServers())
                .as("MCP server must be removed from settings after toggle")
                .doesNotContainKey("my-server");
            assertThat(snap.mcpServers())
                .as("snapshot must no longer contain the removed server")
                .doesNotContainKey("my-server");
        }

        @Test
        @DisplayName("should be a no-op when server is not in settings")
        void toggleMcp_absentServer_isNoOp() {
            var snap = actions.toggleMcp("nonexistent", projectDir);

            assertThat(snap.mcpServers())
                .as("toggling an absent MCP server must not add it to the snapshot")
                .doesNotContainKey("nonexistent");
            assertThat(snap.mcps())
                .as("no MCP servers were ever configured, so the snapshot must list none")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("addStack()")
    class AddStack {

        @Test
        @DisplayName("should append the stack line to CLAUDE.md marker block")
        void addStack_appendsLineToMarkerBlock() throws Exception {
            var claudeMd = projectDir.resolve(".claude/CLAUDE.md");
            Files.writeString(claudeMd,
                "<!-- ccx:begin -->\n@~/.claude/stacks/java.md\n<!-- ccx:end -->\n");

            actions.addStack("@~/.claude/stacks/go.md", projectDir);

            var lines = claudeMdService.readMarkerBlock(claudeMd);
            assertThat(lines)
                .as("addStack must append the new line to the marker block")
                .contains("@~/.claude/stacks/go.md");
        }

        @Test
        @DisplayName("should preserve existing lines when adding a new stack")
        void addStack_preservesExistingLines() throws Exception {
            var claudeMd = projectDir.resolve(".claude/CLAUDE.md");
            Files.writeString(claudeMd,
                "<!-- ccx:begin -->\n@~/.claude/stacks/java.md\n<!-- ccx:end -->\n");

            actions.addStack("@~/.claude/stacks/go.md", projectDir);

            var lines = claudeMdService.readMarkerBlock(claudeMd);
            assertThat(lines)
                .as("existing stack lines must be preserved when adding a new one")
                .contains("@~/.claude/stacks/java.md");
        }

        @Test
        @DisplayName("should be idempotent when the line is already present")
        void addStack_alreadyPresent_isNoOp() throws Exception {
            var claudeMd = projectDir.resolve(".claude/CLAUDE.md");
            Files.writeString(claudeMd,
                "<!-- ccx:begin -->\n@~/.claude/stacks/java.md\n<!-- ccx:end -->\n");

            actions.addStack("@~/.claude/stacks/java.md", projectDir);

            var lines = claudeMdService.readMarkerBlock(claudeMd);
            assertThat(lines)
                .as("addStack on already-present line must not duplicate it")
                .containsExactly("@~/.claude/stacks/java.md");
        }

        @Test
        @DisplayName("should return a fresh snapshot reflecting the newly added stack line")
        void addStack_returnsUpdatedSnapshot() throws Exception {
            var claudeMd = projectDir.resolve(".claude/CLAUDE.md");
            Files.writeString(claudeMd, "<!-- ccx:begin -->\n<!-- ccx:end -->\n");

            var snap = actions.addStack("@~/.claude/stacks/go.md", projectDir);

            assertThat(snap.stacks())
                .as("returned snapshot must reflect the newly added stack line")
                .contains("@~/.claude/stacks/go.md");
        }
    }

    @Nested
    @DisplayName("removeStack()")
    class RemoveStack {

        @Test
        @DisplayName("should remove matching stack line from CLAUDE.md marker block")
        void removeStack_presentLine_removesIt() throws IOException {
            var claudeMd = projectDir.resolve(".claude/CLAUDE.md");
            Files.writeString(claudeMd,
                "<!-- ccx:begin -->\n@~/.claude/stacks/java.md\n@~/.claude/stacks/go.md\n<!-- ccx:end -->\n");

            var snap = actions.removeStack("@~/.claude/stacks/java.md", projectDir);

            assertThat(snap.stacks())
                .as("removed stack must no longer appear in snapshot")
                .doesNotContain("@~/.claude/stacks/java.md");
            assertThat(snap.stacks())
                .as("other stacks must be preserved")
                .contains("@~/.claude/stacks/go.md");
        }

        @Test
        @DisplayName("should be a no-op when stack line is not present")
        void removeStack_absentLine_isNoOp() throws IOException {
            var claudeMd = projectDir.resolve(".claude/CLAUDE.md");
            Files.writeString(claudeMd,
                "<!-- ccx:begin -->\n@~/.claude/stacks/java.md\n<!-- ccx:end -->\n");

            var snap = actions.removeStack("@~/.claude/stacks/nonexistent.md", projectDir);

            assertThat(snap.stacks())
                .as("existing stacks must be preserved when removing a non-existent line")
                .contains("@~/.claude/stacks/java.md");
        }
    }

    @Nested
    @DisplayName("enableProfile()")
    class WhenEnablingProfile {

        @TempDir
        Path configDir;

        private TuiActions profileActions;
        private SettingsService profileSettingsService;

        @BeforeEach
        void setUpProfileServices() throws IOException {
            // Write the parent profile: java (no extends, one plugin)
            var javaProfileDir = configDir.resolve("profiles/java");
            Files.createDirectories(javaProfileDir);
            Files.writeString(javaProfileDir.resolve("profile.yaml"),
                "metadata:\n  name: java\nextends: []\nplugins:\n  enable:\n    - java@test-mkt\n");

            // Write the child profile: java-cli (extends java, one additional plugin)
            var javaCliProfileDir = configDir.resolve("profiles/java-cli");
            Files.createDirectories(javaCliProfileDir);
            Files.writeString(javaCliProfileDir.resolve("profile.yaml"),
                "metadata:\n  name: java-cli\nextends:\n  - java\nplugins:\n  enable:\n    - java-cli@test-mkt\n");

            Files.createDirectories(projectDir.resolve(".claude"));

            profileSettingsService = new SettingsService();
            var claudeMdService = new ClaudeMdService();
            var ccxConfig = new CcxConfigService(configDir);
            var profileService = new ProfileService(buildYamlMapper(), ccxConfig);
            var applyService = new ProfileApplyService(
                profileSettingsService, claudeMdService,
                HomeIsolatedServices.pluginMapService(configDir.resolve("no-registry.json")),
                ccxConfig);
            var tuiData = new TuiDataService(profileSettingsService, claudeMdService,
                new com.fasterxml.jackson.databind.ObjectMapper());

            profileActions = new TuiActions(
                profileSettingsService, claudeMdService, tuiData,
                new ProfileLifecycleService(profileService, applyService, new CcxContextService()));
        }

        @Test
        @DisplayName("should successfully activate a profile whose parent is available in the config dir")
        void enableProfile_withExtends_resolvesParentFromRegistry() {
            var snap = profileActions.enableProfile("java-cli", projectDir);

            assertThat(snap)
                .as("enableProfile must return a non-null snapshot even when profile has extends")
                .isNotNull();
            var written = profileSettingsService.read(projectDir);
            assertThat(written.enabledPlugins())
                .as("settings.json must contain the child profile's plugin after activation")
                .containsKey("java-cli@test-mkt");
        }

        @Test
        @DisplayName("should record the dependency as enabled too when enabling a dependent (Wave 6A)")
        void enableProfile_dependent_recordsDependencyEnabled() {
            profileActions.enableProfile("java-cli", projectDir);

            var context = new CcxContextService().read(projectDir);
            assertThat(context.enabledProfiles())
                .as("enabling java-cli must record BOTH java-cli and its dependency java as enabled")
                .containsKey("java-cli")
                .containsKey("java");
        }

        @Test
        @DisplayName("should skip a closure member that is already enabled, leaving its record untouched")
        void enableProfile_alreadyEnabledMember_isSkipped() {
            profileActions.enableProfile("java", projectDir);
            var firstEnabledAt = new CcxContextService().read(projectDir)
                .enabledProfiles().get("java").enabledAt();

            profileActions.enableProfile("java-cli", projectDir);

            var context = new CcxContextService().read(projectDir);
            assertThat(context.enabledProfiles().get("java").enabledAt())
                .as("an already-enabled closure member must be skipped, not re-applied and re-stamped")
                .isEqualTo(firstEnabledAt);
            assertThat(profileActions.consumeLastError())
                .as("skipping an already-enabled member is silent — it is not a TUI error")
                .isNull();
        }

        private com.fasterxml.jackson.databind.ObjectMapper buildYamlMapper() {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
            mapper.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            mapper.enable(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
            return mapper;
        }
    }
}
