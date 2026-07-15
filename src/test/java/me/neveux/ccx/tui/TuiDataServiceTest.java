// Wave 2 — TDD baseline (Phase 3): tests MUST fail until TuiSnapshot is rewritten
// to the richer shape defined in plan 012, phase 3.
package me.neveux.ccx.tui;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import me.neveux.ccx.model.CcxContext;
import me.neveux.ccx.model.MarketplaceSource;
import me.neveux.ccx.model.McpServer;
import me.neveux.ccx.model.ProfileDriftState;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TuiDataService")
class TuiDataServiceTest {

    @TempDir
    Path projectDir;

    @TempDir
    Path globalRoot;

    @TempDir
    Path ccxConfigDir;

    private SettingsService settingsService;
    private TuiDataService service;

    @BeforeEach
    void setUp() throws Exception {
        settingsService = new SettingsService();
        var javaProfileDir = ccxConfigDir.resolve("profiles/java");
        Files.createDirectories(javaProfileDir);
        Files.writeString(javaProfileDir.resolve("profile.yaml"),
            "metadata:\n  name: java\n  description: Java/Maven projects\nplugins:\n  enable:\n    - java@claude-java-stack\nrules:\n  files:\n    - java\n");
        var yamlMapper = new ObjectMapper(new YAMLFactory());
        yamlMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        yamlMapper.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
        var profileService = new ProfileService(yamlMapper, new CcxConfigService(ccxConfigDir));
        service = new TuiDataService(settingsService, new ClaudeMdService(), new ObjectMapper(),
            new CcxContextService(), profileService, new PluginMapService(),
            new CcxConfigService(ccxConfigDir));
    }

    private void writeSettings(Settings settings) {
        settingsService.write(settings, projectDir);
    }

    private void writeClaudeMd(String content) throws Exception {
        var claudeMd = projectDir.resolve(".claude/CLAUDE.md");
        Files.createDirectories(claudeMd.getParent());
        Files.writeString(claudeMd, content);
    }

    // -----------------------------------------------------------------------
    // WhenEmpty
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when no settings file and no CLAUDE.md exist")
    class WhenEmpty {

        @Test
        @DisplayName("should return empty plugins list")
        void snapshot_emptyDir_returnsEmptyPlugins() {
            var snapshot = service.snapshot(projectDir, globalRoot);

            assertThat(snapshot.plugins())
                .as("plugins must be empty when no settings exist")
                .isEmpty();
        }

        @Test
        @DisplayName("should return empty claudeMdEntries list")
        void snapshot_emptyDir_returnsEmptyClaudeMdEntries() {
            var snapshot = service.snapshot(projectDir, globalRoot);

            assertThat(snapshot.claudeMdEntries())
                .as("claudeMdEntries must be empty when no CLAUDE.md exists")
                .isEmpty();
        }

        @Test
        @DisplayName("should return empty mcps list")
        void snapshot_emptyDir_returnsEmptyMcps() {
            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.mcps())
                .as("mcps must be empty when no settings exist")
                .isEmpty();
        }

    }

    // -----------------------------------------------------------------------
    // WhenPluginsEnabled
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when settings.json has enabled plugins")
    class WhenPluginsEnabled {

        @Test
        @DisplayName("should return a PluginEntry with PROJECT_ENABLED status for each enabled plugin")
        void snapshot_withEnabledPlugins_returnsProjectEnabledEntries() {
            var settings = new Settings().withEnabledPlugins(Map.of(
                "java@claude-java-stack", true,
                "quarkus@claude-java-stack", true
            ));
            writeSettings(settings);

            var snapshot = service.snapshot(projectDir, globalRoot);

            assertThat(snapshot.plugins())
                .as("plugins must contain one entry per enabled plugin")
                .hasSize(2);

            assertThat(snapshot.plugins())
                .as("all entries from settings.json must have PROJECT_ENABLED status")
                .allMatch(p -> p.status() == TuiSnapshot.PluginStatus.PROJECT_ENABLED);
        }

        @Test
        @DisplayName("should include qualified name in each PluginEntry")
        void snapshot_withEnabledPlugins_entryContainsQualifiedName() {
            var settings = new Settings().withEnabledPlugins(Map.of("java@claude-java-stack", true));
            writeSettings(settings);

            var snapshot = service.snapshot(projectDir, globalRoot);

            assertThat(snapshot.plugins())
                .as("plugin entry must expose the qualified plugin name")
                .extracting(TuiSnapshot.PluginEntry::qualifiedName)
                .containsExactly("java@claude-java-stack");
        }

        @Test
        @DisplayName("should expose shortName and marketplace on PluginEntry")
        void snapshot_withEnabledPlugins_entryHasShortNameAndMarketplace() {
            var settings = new Settings().withEnabledPlugins(Map.of("java@claude-java-stack", true));
            writeSettings(settings);

            var snapshot = service.snapshot(projectDir, globalRoot);

            assertThat(snapshot.plugins())
                .as("plugin entry must expose shortName")
                .extracting(TuiSnapshot.PluginEntry::shortName)
                .containsExactly("java");

            assertThat(snapshot.plugins())
                .as("plugin entry must expose marketplace")
                .extracting(TuiSnapshot.PluginEntry::marketplace)
                .containsExactly("claude-java-stack");
        }

    }

    // -----------------------------------------------------------------------
    // WhenStacksConfigured
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when CLAUDE.md has a ccx marker block with stacks")
    class WhenStacksConfigured {

        @Test
        @DisplayName("should return StackEntry list from the marker block")
        void snapshot_withStacksInClaudeMd_returnsStackEntries() throws Exception {
            writeClaudeMd("""
                # My Project
                <!-- ccx:begin -->
                @~/.claude/stacks/java.md
                @~/.claude/stacks/quarkus.md
                <!-- ccx:end -->
                """);

            var snapshot = service.snapshot(projectDir, globalRoot);

            assertThat(snapshot.claudeMdEntries())
                .as("claudeMdEntries must contain all lines from the ccx marker block")
                .extracting(TuiSnapshot.StackEntry::line)
                .contains("@~/.claude/stacks/java.md", "@~/.claude/stacks/quarkus.md");
        }

        @Test
        @DisplayName("should return empty claudeMdEntries when CLAUDE.md exists but has no marker block")
        void snapshot_withClaudeMdButNoMarkerBlock_returnsEmptyClaudeMdEntries() throws Exception {
            writeClaudeMd("# My Project\n\nNo ccx block here.\n");

            var snapshot = service.snapshot(projectDir, globalRoot);

            assertThat(snapshot.claudeMdEntries())
                .as("claudeMdEntries must be empty when no ccx marker block is present")
                .isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // WhenMissingStackRef
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when CLAUDE.md contains a stack ref that does not exist on disk")
    class WhenMissingStackRef {


        @Test
        @DisplayName("missing stack ref should appear in claudeMdEntries with source MISSING")
        void snapshot_withMissingStackRef_claudeMdEntryHasSourceMissing() throws Exception {
            writeClaudeMd("""
                <!-- ccx:begin -->
                @/nonexistent/path/to/missing-stack.md
                <!-- ccx:end -->
                """);

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.claudeMdEntries())
                .as("claudeMdEntries must contain a MISSING entry for the absent stack ref")
                .anySatisfy(entry -> {
                    assertThat(entry.line()).isEqualTo("@/nonexistent/path/to/missing-stack.md");
                    assertThat(entry.source()).isEqualTo(TuiSnapshot.StackSource.MISSING);
                });
        }
    }

    // -----------------------------------------------------------------------
    // WhenMcpServers (updated to use mcps list)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when settings.json has MCP servers")
    class WhenMcpServers {

        @Test
        @DisplayName("should populate mcps list from settings")
        void snapshot_withMcpServers_returnsMcpsList() {
            var server = new McpServer("http", "https://mcp.example.com", null, null);
            var settings = new Settings().withMcpServers(Map.of("my-server", server));
            writeSettings(settings);

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.mcps())
                .as("mcps must contain the configured server")
                .hasSize(1);

            assertThat(snapshot.mcps())
                .as("McpEntry name must match the key in settings")
                .extracting(TuiSnapshot.McpEntry::name)
                .containsExactly("my-server");

            assertThat(snapshot.mcps().get(0).config().type())
                .as("McpEntry.config() must preserve the McpServer type")
                .isEqualTo("http");
        }


        @Test
        @DisplayName("McpEntry should expose enabled flag")
        void snapshot_withMcpServer_mcpEntryHasEnabledFlag() {
            var server = new McpServer("http", "https://mcp.example.com", null, null);
            var settings = new Settings().withMcpServers(Map.of("my-server", server));
            writeSettings(settings);

            var snapshot = service.snapshot(projectDir);

            // An MCP server present in settings is considered enabled
            assertThat(snapshot.mcps())
                .as("McpEntry.enabled() must be non-null (true or false)")
                .extracting(TuiSnapshot.McpEntry::enabled)
                .doesNotContainNull();
        }
    }

    // -----------------------------------------------------------------------
    // WhenMalformedSettings
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when settings.json is malformed")
    class WhenMalformedSettings {


        @Test
        @DisplayName("should still return a non-null snapshot when settings.json is malformed")
        void snapshot_withMalformedSettingsJson_doesNotThrow() throws Exception {
            var claudeDir = projectDir.resolve(".claude");
            Files.createDirectories(claudeDir);
            Files.writeString(claudeDir.resolve("settings.json"), "{ this is not valid json }}}");

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot)
                .as("snapshot must not be null even when settings.json is malformed")
                .isNotNull();
        }
    }

    // -----------------------------------------------------------------------
    // WhenRulesDirectoryExists
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when rules directory exists")
    class WhenRulesDirectoryExists {

        @Test
        @DisplayName("rulesCount counts every regular file under .claude/rules/")
        void snapshot_withRulesDir_rulesCountMatchesFileCount() throws Exception {
            var rulesDir = projectDir.resolve(".claude/rules");
            Files.createDirectories(rulesDir);
            Files.writeString(rulesDir.resolve("java.md"), "# java rules");
            Files.writeString(rulesDir.resolve("git.md"), "# git rules");
            Files.writeString(rulesDir.resolve("security.md"), "# security rules");

            var snap = service.snapshot(projectDir, globalRoot);

            assertThat(snap.overview().rulesExists())
                .as("rulesExists must be true when .claude/rules/ is a directory")
                .isTrue();
            assertThat(snap.overview().rulesCount())
                .as("rulesCount must equal the number of regular files in .claude/rules/")
                .isEqualTo(3);
            assertThat(snap.overview().rulesFileNames())
                .as("rulesFileNames must list every rule file present on disk")
                .containsExactlyInAnyOrder("java.md", "git.md", "security.md");
        }

        @Test
        @DisplayName("rulesExists is false and rulesCount is 0 when .claude/rules/ is absent")
        void snapshot_withoutRulesDir_rulesAbsentAndCountZero() {
            var snap = service.snapshot(projectDir, globalRoot);

            assertThat(snap.overview().rulesExists())
                .as("rulesExists must be false when .claude/rules/ does not exist")
                .isFalse();
            assertThat(snap.overview().rulesCount())
                .as("rulesCount must be 0 when .claude/rules/ does not exist")
                .isZero();
            assertThat(snap.overview().rulesFileNames())
                .as("rulesFileNames must be empty when .claude/rules/ does not exist")
                .isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // WhenDependencyBroken (Wave 6)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when an enabled profile has a missing dependency")
    class WhenDependencyBroken {

        @Test
        @DisplayName("dependent profile resolves to BROKEN when its dependency is not enabled")
        void snapshot_dependentWithMissingDep_isBroken() throws Exception {
            // java already exists from setUp; add java-functional extending java
            var jfDir = ccxConfigDir.resolve("profiles/java-functional");
            Files.createDirectories(jfDir);
            Files.writeString(jfDir.resolve("profile.yaml"),
                "metadata:\n  name: java-functional\nextends:\n  - java\n");

            // Context records ONLY java-functional as enabled (java is its missing dependency)
            var ccxDir = projectDir.resolve(".ccx");
            Files.createDirectories(ccxDir);
            Files.writeString(ccxDir.resolve("context.json"), """
                {"version":"1","enabledProfiles":{"java-functional":{"enabledAt":"2024-01-15T10:00:00Z",
                "snapshot":{"plugins":[],"rules":[],"permissionsAllow":[],"permissionsDeny":[]}}}}
                """);

            var snap = service.snapshot(projectDir, globalRoot);

            assertThat(snap.profiles())
                .as("java-functional must resolve to BROKEN when its dependency java is not enabled")
                .anyMatch(p -> p.name().equals("java-functional")
                    && p.driftState() == ProfileDriftState.BROKEN);
        }
    }

    // -----------------------------------------------------------------------
    // WhenGlobalPluginsEnabled
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("globally-enabled plugins")
    class WhenGlobalPluginsEnabled {

        @Test
        @DisplayName("global plugin not in project settings appears as GLOBALLY_ENABLED")
        void snapshot_globalPlugin_appearsAsGloballyEnabled() {
            var globalSettings = new Settings().withEnabledPlugins(Map.of("thorn@meta", true));
            settingsService.write(globalSettings, globalRoot);

            var snap = service.snapshot(projectDir, globalRoot);

            assertThat(snap.plugins())
                .as("global-only plugin must appear as GLOBALLY_ENABLED")
                .anyMatch(p -> p.qualifiedName().equals("thorn@meta")
                    && p.status() == TuiSnapshot.PluginStatus.GLOBALLY_ENABLED);
        }

        @Test
        @DisplayName("plugin in both project and global settings appears as both PROJECT_ENABLED and GLOBALLY_ENABLED")
        void snapshot_pluginInBothScopes_appearsTwiceWithBothStatuses() {
            var projectSettings = new Settings().withEnabledPlugins(Map.of("java@stack", true));
            settingsService.write(projectSettings, projectDir);

            var globalSettings = new Settings().withEnabledPlugins(Map.of("java@stack", true, "thorn@meta", true));
            settingsService.write(globalSettings, globalRoot);

            var snap = service.snapshot(projectDir, globalRoot);

            var javaEntries = snap.plugins().stream()
                .filter(p -> p.qualifiedName().equals("java@stack")).toList();
            assertThat(javaEntries)
                .as("java@stack must appear twice — once per scope")
                .hasSize(2);
            assertThat(javaEntries)
                .as("java@stack must have a PROJECT_ENABLED entry")
                .anyMatch(p -> p.status() == TuiSnapshot.PluginStatus.PROJECT_ENABLED);
            assertThat(javaEntries)
                .as("java@stack must have a GLOBALLY_ENABLED entry")
                .anyMatch(p -> p.status() == TuiSnapshot.PluginStatus.GLOBALLY_ENABLED);
        }
    }

    // -----------------------------------------------------------------------
    // WhenMarketplaceHasUnenabled
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when marketplace has plugins not present in any settings")
    class WhenMarketplaceHasUnenabled {

        @Test
        @DisplayName("should include AVAILABLE entry for marketplace plugin not in enabled list")
        void buildPluginEntries_includesAvailablePlugins_whenMarketplaceHasUnenabled(
                @TempDir Path marketplaceDir) throws Exception {
            // Arrange: marketplace has a "java" subdir → qualified key "java@my-mkt"
            Files.createDirectories(marketplaceDir.resolve("java"));

            // enabledPlugins has a DIFFERENT key — "java@my-mkt" is NOT enabled
            var src = new MarketplaceSource("my-mkt", marketplaceDir.toAbsolutePath().toString(), null, null);
            var settings = new Settings()
                .withEnabledPlugins(Map.of("other@some-stack", true))
                .withExtraKnownMarketplaces(List.of(src));
            writeSettings(settings);

            // Use full 8-arg constructor so PluginMapService and CcxConfigService are wired in
            var svc = new TuiDataService(settingsService, new ClaudeMdService(), new ObjectMapper(),
                new CcxContextService(), new ProfileService(), new PluginMapService(),
                new CcxConfigService(ccxConfigDir));

            // Act
            var snap = svc.snapshot(projectDir, globalRoot);

            // Assert: at least one AVAILABLE entry for "java@my-mkt"
            assertThat(snap.plugins())
                .as("snapshot must contain an AVAILABLE entry for the unenabled marketplace plugin")
                .anySatisfy(entry -> {
                    assertThat(entry.qualifiedName()).isEqualTo("java@my-mkt");
                    assertThat(entry.status()).isEqualTo(TuiSnapshot.PluginStatus.AVAILABLE);
                });

            // Assert: enabled entries come before AVAILABLE entries (enabled-first ordering)
            var statuses = snap.plugins().stream()
                .map(TuiSnapshot.PluginEntry::status)
                .toList();
            var firstAvailableIndex = statuses.indexOf(TuiSnapshot.PluginStatus.AVAILABLE);
            var lastEnabledIndex = statuses.stream()
                .reduce(-1,
                    (idx, s) -> {
                        int cur = statuses.indexOf(s);
                        return (s == TuiSnapshot.PluginStatus.PROJECT_ENABLED
                            || s == TuiSnapshot.PluginStatus.GLOBALLY_ENABLED)
                            ? Math.max(idx, cur) : idx;
                    },
                    Math::max);
            assertThat(firstAvailableIndex)
                .as("AVAILABLE entries must appear after all enabled entries")
                .isGreaterThan(lastEnabledIndex);
        }

        @Test
        @DisplayName("plugin description read from README.md in marketplace dir")
        void pluginEntry_description_readFromReadme(@TempDir Path marketplaceDir) throws Exception {
            var pluginDir = marketplaceDir.resolve("bark");
            Files.createDirectories(pluginDir);
            Files.writeString(pluginDir.resolve("README.md"), "# bark\nA shell workflow plugin.\n");

            var src = new MarketplaceSource("my-mkt", marketplaceDir.toAbsolutePath().toString(), null, null);
            var settings = new Settings()
                .withEnabledPlugins(Map.of("other@some-stack", true))
                .withExtraKnownMarketplaces(List.of(src));
            settingsService.write(settings, projectDir);

            var svc = new TuiDataService(settingsService, new ClaudeMdService(), new ObjectMapper(),
                new CcxContextService(), new ProfileService(), new PluginMapService(),
                new CcxConfigService(ccxConfigDir));
            var snap = svc.snapshot(projectDir, globalRoot);

            assertThat(snap.plugins())
                .as("bark should appear as AVAILABLE with a description from README")
                .anySatisfy(p -> {
                    assertThat(p.shortName()).isEqualTo("bark");
                    assertThat(p.description())
                        .as("description must be the first sentence extracted from README.md")
                        .isEqualTo("A shell workflow plugin.");
                });
        }
    }

    // -----------------------------------------------------------------------
    // WhenOverviewData
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when building overview data")
    class WhenOverviewData {

        @Test
        @DisplayName("overview has correct project and global plugin counts")
        void snapshot_overview_hasCorrectPluginCounts() {
            var projectSettings = new Settings().withEnabledPlugins(Map.of("java@stack", true, "quarkus@stack", true));
            settingsService.write(projectSettings, projectDir);

            var globalSettings = new Settings().withEnabledPlugins(Map.of("thorn@meta", true));
            settingsService.write(globalSettings, globalRoot);

            var snap = service.snapshot(projectDir, globalRoot);

            assertThat(snap.overview().projectPluginCount())
                .as("overview.projectPluginCount must equal project-scoped enabled plugins")
                .isEqualTo(2);

            assertThat(snap.overview().globalPluginCount())
                .as("overview.globalPluginCount must equal global-scoped enabled plugins not overridden by project")
                .isEqualTo(1);
        }
    }

    // -----------------------------------------------------------------------
    // WhenBuildingProfiles
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when building profiles list")
    class WhenBuildingProfiles {

        @Test
        @DisplayName("should return non-empty profiles list with built-in profiles")
        void buildsProfilesList_withBuiltInProfiles() {
            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.profiles())
                .as("profiles list must not be empty — built-in profiles should always be present")
                .isNotEmpty();

            assertThat(snapshot.profiles().stream().map(TuiSnapshot.ProfileEntry::name).toList())
                .as("profiles list must contain the java built-in profile")
                .contains("java");
        }

        @Test
        @DisplayName("should populate description for java profile")
        void profileEntry_hasDescription() {
            var snapshot = service.snapshot(projectDir);

            var javaProfile = snapshot.profiles().stream()
                .filter(p -> "java".equals(p.name()))
                .findFirst();

            assertThat(javaProfile)
                .as("java profile must be present")
                .isPresent();
            assertThat(javaProfile.get().description())
                .as("java profile description must match the profile.yaml metadata.description")
                .isEqualTo("Java/Maven projects");
        }

        @Test
        @DisplayName("all built-in profiles have non-null driftState")
        void profileEntry_hasNonNullDriftState() {
            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.profiles())
                .as("every built-in profile must have a non-null driftState")
                .allSatisfy(p -> assertThat(p.driftState()).isNotNull());
        }

        @Test
        @DisplayName("all built-in profiles have non-null previewMarkdown")
        void profileEntry_hasNonNullPreviewMarkdown() {
            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.profiles())
                .as("every built-in profile must have a non-null previewMarkdown")
                .allSatisfy(p -> assertThat(p.previewMarkdown()).isNotNull());
        }
    }

    // -----------------------------------------------------------------------
    // WhenProfileDriftState
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when detecting profile drift state")
    class WhenProfileDriftState {

        private void writeContextWithProfile(String profileName, List<String> plugins) throws Exception {
            var ccxContextService = new CcxContextService();
            var snapshot = new CcxContext.ProfileSnapshot(plugins, List.of(), List.of(), List.of(), null, null, null, null);
            var record = new CcxContext.ProfileRecord(Instant.now(), snapshot);
            var ctx = new CcxContext("1", Map.of(profileName, record));
            ccxContextService.write(ctx, projectDir);
        }

        @Test
        @DisplayName("profileEntry driftState is DISABLED when no context.json exists")
        void profileEntry_disabledWhenNotInContextJson() {
            // No context.json written — all profiles should be DISABLED
            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.profiles())
                .as("all built-in profiles must be DISABLED when no context.json exists")
                .allSatisfy(p ->
                    assertThat(p.driftState())
                        .as("profile %s must be DISABLED", p.name())
                        .isEqualTo(ProfileDriftState.DISABLED)
                );
        }

        @Test
        @DisplayName("profileEntry is ENABLED when context.json records the profile as enabled with matching plugins")
        void profileEntry_enabledWhenInContextJson() throws Exception {
            // Write context.json recording java as enabled with no plugins to check
            // (empty plugin list means no drift from plugin checks)
            writeContextWithProfile("java", List.of());

            var snapshot = service.snapshot(projectDir);

            var javaProfile = snapshot.profiles().stream()
                .filter(p -> "java".equals(p.name()))
                .findFirst();

            assertThat(javaProfile)
                .as("java profile must be present in snapshot")
                .isPresent();
            assertThat(javaProfile.get().driftState())
                .as("java profile must be ENABLED when recorded in context.json with no plugin drift")
                .isEqualTo(ProfileDriftState.ENABLED);
        }

        @Test
        @DisplayName("profileEntry is ALTERED when context.json records a plugin that is not in live settings")
        void profileEntry_alteredWhenPluginMissing() throws Exception {
            // Record java profile with a plugin that is NOT in settings.json
            writeContextWithProfile("java", List.of("missing-plugin@some-stack"));

            var snapshot = service.snapshot(projectDir);

            var javaProfile = snapshot.profiles().stream()
                .filter(p -> "java".equals(p.name()))
                .findFirst();

            assertThat(javaProfile)
                .as("java profile must be present")
                .isPresent();
            assertThat(javaProfile.get().driftState())
                .as("java profile must be ALTERED when a recorded plugin is absent from live settings")
                .isEqualTo(ProfileDriftState.ALTERED);
        }
    }

    // -----------------------------------------------------------------------
    // WhenStackEntries (source classification)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when classifying stack entry sources")
    class WhenStackEntries {

        @Test
        @DisplayName("StackEntry source is PROJECT when absolute path fragment exists on disk")
        void stackEntry_sourceIsProjectWhenAbsolutePathExists() throws Exception {
            var fragDir = projectDir.resolve("fragments");
            Files.createDirectories(fragDir);
            Files.writeString(fragDir.resolve("java.md"), "# Java fragment content");

            var absPath = fragDir.resolve("java.md").toAbsolutePath().normalize().toString();
            writeClaudeMd("<!-- ccx:begin -->\n@" + absPath + "\n<!-- ccx:end -->\n");

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.claudeMdEntries())
                .as("stack entry pointing to existing absolute path must have source PROJECT")
                .anySatisfy(entry -> {
                    assertThat(entry.line()).isEqualTo("@" + absPath);
                    assertThat(entry.source()).isEqualTo(TuiSnapshot.StackSource.PROJECT);
                });
        }

        @Test
        @DisplayName("StackEntry source is MISSING when referenced file does not exist")
        void stackEntry_sourceIsMissingWhenFileAbsent() throws Exception {
            writeClaudeMd("""
                <!-- ccx:begin -->
                @~/.claude/stacks/nonexistent.md
                <!-- ccx:end -->
                """);

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.claudeMdEntries())
                .as("stack entry pointing to a missing file must have source MISSING")
                .anySatisfy(entry -> {
                    assertThat(entry.line()).isEqualTo("@~/.claude/stacks/nonexistent.md");
                    assertThat(entry.source()).isEqualTo(TuiSnapshot.StackSource.MISSING);
                });
        }

        @Test
        @DisplayName("StackEntry exposes name extracted from path")
        void stackEntry_hasExtractedName() throws Exception {
            writeClaudeMd("""
                <!-- ccx:begin -->
                @~/.claude/stacks/java.md
                <!-- ccx:end -->
                """);

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.claudeMdEntries())
                .as("stack entry must expose an extracted name")
                .anySatisfy(entry -> {
                    assertThat(entry.line()).isEqualTo("@~/.claude/stacks/java.md");
                    assertThat(entry.name()).isEqualTo("java");
                });
        }
    }



    // -----------------------------------------------------------------------
    // Wave 5 TDD — AVAILABLE stack entries from global claudemd dir
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when discovering available global claudemd fragments")
    class WhenAvailableGlobalClaudeMd {

        @Test
        @DisplayName("referenced fragment with existing file has source PROJECT")
        void stackEntry_referencedFragmentWithExistingFile_isProject() throws Exception {
            var fragDir = projectDir.resolve("fragments");
            Files.createDirectories(fragDir);
            Files.writeString(fragDir.resolve("java.md"), "# Java fragment");

            var absPath = fragDir.resolve("java.md").toAbsolutePath().normalize().toString();
            writeClaudeMd("<!-- ccx:begin -->\n@" + absPath + "\n<!-- ccx:end -->\n");

            var snap = service.snapshot(projectDir, globalRoot);

            assertThat(snap.claudeMdEntries())
                .as("referenced existing file must have source PROJECT")
                .anySatisfy(e -> {
                    assertThat(e.name()).isEqualTo("java");
                    assertThat(e.source()).isEqualTo(TuiSnapshot.StackSource.PROJECT);
                });
        }

        @Test
        @DisplayName("referenced fragment pointing to non-existent file has source MISSING")
        void stackEntry_referencedFragmentMissing_isMissing() throws Exception {
            writeClaudeMd("<!-- ccx:begin -->\n@/nonexistent/path/java.md\n<!-- ccx:end -->\n");

            var snap = service.snapshot(projectDir, globalRoot);

            assertThat(snap.claudeMdEntries())
                .as("referenced non-existent file must have source MISSING")
                .anySatisfy(e -> {
                    assertThat(e.name()).isEqualTo("java");
                    assertThat(e.source()).isEqualTo(TuiSnapshot.StackSource.MISSING);
                });
        }

        @Test
        @DisplayName("non-@ line in marker block has source FRAGMENT")
        void stackEntry_nonAtLine_isFragment() throws Exception {
            writeClaudeMd("<!-- ccx:begin -->\nsome plain content\n<!-- ccx:end -->\n");

            var snap = service.snapshot(projectDir, globalRoot);

            assertThat(snap.claudeMdEntries())
                .as("non-@ line must have source FRAGMENT")
                .anySatisfy(e -> {
                    assertThat(e.line()).isEqualTo("some plain content");
                    assertThat(e.source()).isEqualTo(TuiSnapshot.StackSource.FRAGMENT);
                });
        }
    }



    // -----------------------------------------------------------------------
    // Wave 6 TDD — profile preview content (env, ignore, file listings, error)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when building profile preview markdown")
    class WhenProfilePreviewMarkdown {

        private static final String JAVA_YAML_WITH_ENV_IGNORE = """
            metadata:
              name: java
              description: Java/Maven projects
            plugins:
              enable:
                - java@claude-java-stack
            env:
              ENABLE_LSP_TOOL: "1"
            ignore:
              - "target/"
              - ".mvn/"
            """;

        @Test
        @DisplayName("preview includes env key when profile has env entries")
        void buildProfilePreviewMarkdown_withEnvEntries_previewContainsKey() throws Exception {
            Files.writeString(ccxConfigDir.resolve("profiles/java/profile.yaml"), JAVA_YAML_WITH_ENV_IGNORE);

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.profiles())
                .as("java profile must be present")
                .anySatisfy(p -> {
                    assertThat(p.name()).isEqualTo("java");
                    assertThat(p.previewMarkdown())
                        .as("previewMarkdown must contain env key ENABLE_LSP_TOOL")
                        .contains("ENABLE_LSP_TOOL");
                });
        }

        @Test
        @DisplayName("preview includes ignore pattern when profile has ignore entries")
        void buildProfilePreviewMarkdown_withIgnorePatterns_previewContainsPattern() throws Exception {
            Files.writeString(ccxConfigDir.resolve("profiles/java/profile.yaml"), JAVA_YAML_WITH_ENV_IGNORE);

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.profiles())
                .anySatisfy(p -> {
                    assertThat(p.name()).isEqualTo("java");
                    assertThat(p.previewMarkdown())
                        .as("previewMarkdown must contain ignore pattern target/")
                        .contains("target/");
                });
        }

        @Test
        @DisplayName("preview lists fragment files from the profile claudemd/ dir")
        void buildProfilePreviewMarkdown_withFragmentFiles_previewListsFilenames() throws Exception {
            Files.createDirectories(ccxConfigDir.resolve("profiles/java/claudemd"));
            Files.writeString(ccxConfigDir.resolve("profiles/java/claudemd/java.md"), "# Java fragment");

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.profiles())
                .anySatisfy(p -> {
                    assertThat(p.name()).isEqualTo("java");
                    assertThat(p.previewMarkdown())
                        .as("previewMarkdown must list fragment file java.md")
                        .contains("java.md");
                });
        }

        @Test
        @DisplayName("preview lists rule files from the profile rules/ dir")
        void buildProfilePreviewMarkdown_withRuleFiles_previewListsFilenames() throws Exception {
            Files.createDirectories(ccxConfigDir.resolve("profiles/java/rules"));
            Files.writeString(ccxConfigDir.resolve("profiles/java/rules/java-safety.md"), "# Java safety rules");

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.profiles())
                .anySatisfy(p -> {
                    assertThat(p.name()).isEqualTo("java");
                    assertThat(p.previewMarkdown())
                        .as("previewMarkdown must list rule file java-safety.md")
                        .contains("java-safety.md");
                });
        }

        @Test
        @DisplayName("preview lists command files from the profile commands/ dir")
        void buildProfilePreviewMarkdown_withCommandFiles_previewListsFilenames() throws Exception {
            Files.createDirectories(ccxConfigDir.resolve("profiles/java/commands"));
            Files.writeString(ccxConfigDir.resolve("profiles/java/commands/review.md"), "# review command");

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.profiles())
                .anySatisfy(p -> {
                    assertThat(p.name()).isEqualTo("java");
                    assertThat(p.previewMarkdown())
                        .as("previewMarkdown must list command file review.md")
                        .contains("review.md");
                });
        }

        @Test
        @DisplayName("malformed profile YAML produces non-empty error preview")
        void buildProfiles_withMalformedYaml_previewMarkdownIsNonEmpty() throws Exception {
            var badDir = ccxConfigDir.resolve("profiles/broken");
            Files.createDirectories(badDir);
            Files.writeString(badDir.resolve("profile.yaml"), "{ this: is: not: valid: yaml :");

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.profiles())
                .as("broken profile must appear in the list")
                .anySatisfy(p -> {
                    assertThat(p.name()).isEqualTo("broken");
                    assertThat(p.previewMarkdown())
                        .as("previewMarkdown must be non-empty when profile.yaml is malformed")
                        .isNotEmpty();
                });
        }
    }

    // -----------------------------------------------------------------------
    // Wave 6 TDD — empty profiles dir
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when no profiles are configured")
    class WhenNoProfilesConfigured {

        @Test
        @DisplayName("snapshot returns empty profiles list without throwing when profiles dir is absent")
        void snapshot_withNoProfilesDir_returnsEmptyList(@TempDir Path emptyConfigDir) throws Exception {
            var yamlMapper = new ObjectMapper(new YAMLFactory());
            yamlMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            yamlMapper.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
            var emptyProfileService = new ProfileService(yamlMapper, new CcxConfigService(emptyConfigDir));
            var svc = new TuiDataService(settingsService, new ClaudeMdService(), new ObjectMapper(),
                new CcxContextService(), emptyProfileService, new PluginMapService(),
                new CcxConfigService(emptyConfigDir));

            var snapshot = svc.snapshot(projectDir);

            assertThat(snapshot.profiles())
                .as("profiles list must be empty when profiles dir is absent")
                .isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Wave 2 TDD — new OverviewData fields (tests written BEFORE implementation)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when building overview — rich fields")
    class WhenOverviewDataRichFields {

        @Test
        @DisplayName("overview.projectPluginNames contains names of PROJECT_ENABLED plugins")
        void overview_projectPluginNames_containsEnabledPluginShortNames() {
            var s = new Settings().withEnabledPlugins(Map.of("bark@vine", true));
            settingsService.write(s, projectDir);

            var snap = service.snapshot(projectDir, globalRoot);

            assertThat(snap.overview().projectPluginNames())
                .as("projectPluginNames must include enabled plugin qualified names")
                .contains("bark@vine");
        }

        @Test
        @DisplayName("overview.rulesFileNames contains filenames from .claude/rules/")
        void overview_rulesFileNames_containsFilenames() throws Exception {
            var rulesDir = projectDir.resolve(".claude/rules");
            Files.createDirectories(rulesDir);
            Files.writeString(rulesDir.resolve("java.md"), "# rules");

            var snap = service.snapshot(projectDir, globalRoot);

            assertThat(snap.overview().rulesFileNames())
                .as("rulesFileNames must include files present in .claude/rules/")
                .contains("java.md");
        }

        @Test
        @DisplayName("overview claudeIgnore globalPresent=true when .claudeignore is at globalRoot (not inside .claude/)")
        void claudeIgnore_globalPresentWhenAtGlobalRoot() throws Exception {
            Files.writeString(globalRoot.resolve(".claudeignore"), "*.log\n");

            var snap = service.snapshot(projectDir, globalRoot);

            assertThat(snap.overview().claudeIgnore().presentGlobal())
                .as("presentGlobal must be true when .claudeignore is at globalRoot directly")
                .isTrue();
        }

        @Test
        @DisplayName("overview.privateClaudeMdPath is non-null when .claude/CLAUDE.md exists")
        void overview_privateClaudeMdPath_nonNullWhenFileExists() throws Exception {
            var privateClaudeDir = projectDir.resolve(".claude");
            Files.createDirectories(privateClaudeDir);
            Files.writeString(privateClaudeDir.resolve("CLAUDE.md"), "# private instructions\n");

            var snap = service.snapshot(projectDir, globalRoot);

            assertThat(snap.overview().privateClaudeMdPath())
                .as("privateClaudeMdPath must be non-null when .claude/CLAUDE.md exists")
                .isNotNull()
                .endsWith("CLAUDE.md");
        }

        @Test
        @DisplayName("overview.privateClaudeMdPath is null when .claude/CLAUDE.md is absent")
        void overview_privateClaudeMdPath_nullWhenFileAbsent() {
            var snap = service.snapshot(projectDir, globalRoot);

            assertThat(snap.overview().privateClaudeMdPath())
                .as("privateClaudeMdPath must be null when .claude/CLAUDE.md is absent")
                .isNull();
        }

        @Test
        @DisplayName("overview.projectSettingsExists is true when .claude/settings.json exists")
        void overview_projectSettingsExists_trueWhenSettingsPresent() throws Exception {
            var claudeDir = projectDir.resolve(".claude");
            Files.createDirectories(claudeDir);
            Files.writeString(claudeDir.resolve("settings.json"), "{}");

            var snap = service.snapshot(projectDir, globalRoot);

            assertThat(snap.overview().projectSettingsExists())
                .as("projectSettingsExists must be true when .claude/settings.json exists")
                .isTrue();
        }

        @Test
        @DisplayName("overview.projectSettingsExists is false when .claude/settings.json is absent")
        void overview_projectSettingsExists_falseWhenSettingsAbsent() {
            var snap = service.snapshot(projectDir, globalRoot);

            assertThat(snap.overview().projectSettingsExists())
                .as("projectSettingsExists must be false when .claude/settings.json is absent")
                .isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // Plan 039 TDD — fragmentNames includes PROJECT-source (@-referenced) fragments
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Plan 039 — overview.fragmentNames includes @-referenced (PROJECT) fragments")
    class WhenFragmentNamesFromReferencedFragments {

        @Test
        @DisplayName("fragmentNames includes the name of an @-referenced fragment that exists on disk (PROJECT source)")
        void overview_fragmentNames_includesProjectSourcedFragment() throws Exception {
            var fragDir = projectDir.resolve("fragments");
            Files.createDirectories(fragDir);
            Files.writeString(fragDir.resolve("java-quarkus.md"), "# Quarkus fragment");

            var absPath = fragDir.resolve("java-quarkus.md").toAbsolutePath().normalize().toString();
            writeClaudeMd("<!-- ccx:begin -->\n@" + absPath + "\n<!-- ccx:end -->\n");

            var snap = service.snapshot(projectDir, globalRoot);

            // Guard: the entry must resolve to PROJECT source (not FRAGMENT)
            assertThat(snap.claudeMdEntries())
                .as("the @-referenced existing fragment must resolve to PROJECT source")
                .anySatisfy(e -> {
                    assertThat(e.name()).isEqualTo("java-quarkus");
                    assertThat(e.source()).isEqualTo(TuiSnapshot.StackSource.PROJECT);
                });

            // The bug: fragmentNames filters on FRAGMENT source only, so a PROJECT-source
            // @-referenced fragment never appears. After the fix it must be included.
            assertThat(snap.overview().fragmentNames())
                .as("fragmentNames must include the @-referenced PROJECT-source fragment name")
                .contains("java-quarkus");
        }
    }

    // -----------------------------------------------------------------------
    // Wave 4 TDD — profile claudemd fragment discovery
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when discovering fragments from profile claudemd dirs")
    class WhenProfileClaudeMdFragments {

        @Test
        @DisplayName("fragment file in a profile's claudemd/ dir appears as AVAILABLE when not referenced")
        void buildStackEntries_profileClaudeMdFragment_appearsAsAvailable() throws Exception {
            Files.createDirectories(ccxConfigDir.resolve("profiles/java/claudemd"));
            Files.writeString(ccxConfigDir.resolve("profiles/java/claudemd/java-core.md"), "# Java core fragment");

            var snap = service.snapshot(projectDir, globalRoot);

            assertThat(snap.claudeMdEntries())
                .as("fragment from profile claudemd/ must appear as AVAILABLE when not in marker block")
                .anySatisfy(e -> {
                    assertThat(e.name()).isEqualTo("java-core");
                    assertThat(e.source()).isEqualTo(TuiSnapshot.StackSource.AVAILABLE);
                });
        }

        @Test
        @DisplayName("fragment already in marker block is not duplicated as AVAILABLE")
        void buildStackEntries_profileClaudeMdFragment_notDuplicatedWhenReferenced() throws Exception {
            Files.createDirectories(ccxConfigDir.resolve("profiles/java/claudemd"));
            var fragFile = ccxConfigDir.resolve("profiles/java/claudemd/java-core.md");
            Files.writeString(fragFile, "# Java core fragment");

            var absPath = "@" + fragFile.toAbsolutePath().normalize();
            writeClaudeMd("<!-- ccx:begin -->\n" + absPath + "\n<!-- ccx:end -->\n");

            var snap = service.snapshot(projectDir, globalRoot);

            var javaCorEntries = snap.claudeMdEntries().stream()
                .filter(e -> "java-core".equals(e.name()))
                .toList();
            assertThat(javaCorEntries)
                .as("fragment already in marker block must not be duplicated as AVAILABLE")
                .hasSize(1)
                .noneMatch(e -> e.source() == TuiSnapshot.StackSource.AVAILABLE);
        }
    }

    // -----------------------------------------------------------------------
    // Wave 10 TDD — profile preview reads from claudemd/ dir (not YAML model)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when profile preview lists fragments from claudemd/ directory")
    class WhenProfilePreviewFragmentsFromDir {

        @Test
        @DisplayName("CLAUDE.md Fragments H3 section lists disk filenames (not showing 'Empty' when files exist)")
        void buildProfilePreviewMarkdown_fragmentsH3SectionListsDiskFiles() throws Exception {
            Files.createDirectories(ccxConfigDir.resolve("profiles/java/claudemd"));
            Files.writeString(ccxConfigDir.resolve("profiles/java/claudemd/java-core.md"), "# Java core");
            Files.writeString(ccxConfigDir.resolve("profiles/java/claudemd/java-extra.md"), "# Java extra");

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.profiles())
                .as("java profile must be present")
                .anySatisfy(p -> {
                    assertThat(p.name()).isEqualTo("java");
                    // The CLAUDE.md Fragments section must list actual filenames from the dir
                    // Bug: current code reads profile.claudeMd.fragments (usually null) so section shows "Empty"
                    // while appendProfileFiles adds a separate "Fragment files" bolded section
                    var preview = p.previewMarkdown();
                    // After fix: the H3 section "### CLAUDE.md Fragments" must contain the disk file
                    var fragmentsH3Start = preview.indexOf("### CLAUDE.md Fragments");
                    assertThat(fragmentsH3Start)
                        .as("preview must contain a '### CLAUDE.md Fragments' section")
                        .isGreaterThanOrEqualTo(0);
                    var fragmentsSectionText = preview.substring(fragmentsH3Start);
                    assertThat(fragmentsSectionText)
                        .as("CLAUDE.md Fragments section must list java-core.md from disk, not show 'Empty'")
                        .contains("java-core.md")
                        .doesNotContain("Empty");
                });
        }

        @Test
        @DisplayName("no separate bold 'Fragment files' section appears when claudemd/ dir files are in the H3 section")
        void buildProfilePreviewMarkdown_noSeparateFragmentFilesBoldSection() throws Exception {
            Files.createDirectories(ccxConfigDir.resolve("profiles/java/claudemd"));
            Files.writeString(ccxConfigDir.resolve("profiles/java/claudemd/java.md"), "# Java fragment");

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.profiles())
                .anySatisfy(p -> {
                    assertThat(p.name()).isEqualTo("java");
                    var preview = p.previewMarkdown();
                    // After fix: appendProfileFiles for claudemd must be removed
                    // so "**Fragment files**" does not appear as a separate duplicate section
                    assertThat(preview)
                        .as("bold 'Fragment files' section must not appear as a separate section (duplicate of CLAUDE.md Fragments H3)")
                        .doesNotContain("**Fragment files**");
                });
        }

        @Test
        @DisplayName("fragment entries appear as per-item markdown list entries under the H3 section")
        void buildProfilePreviewMarkdown_fragmentsUsesItemListNotInlineJoin() throws Exception {
            Files.createDirectories(ccxConfigDir.resolve("profiles/java/claudemd"));
            Files.writeString(ccxConfigDir.resolve("profiles/java/claudemd/java.md"), "# Java fragment");

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.profiles())
                .anySatisfy(p -> {
                    assertThat(p.name()).isEqualTo("java");
                    assertThat(p.previewMarkdown())
                        .as("fragment entry must appear as a markdown list item `- \\`name\\``")
                        .containsPattern("- `java\\.md`");
                });
        }
    }

    @Nested
    @DisplayName("when profile preview lists rules from rules/ directory")
    class WhenProfilePreviewRulesFromDir {

        @Test
        @DisplayName("Rules H3 section lists disk filenames (not YAML model values which may differ)")
        void buildProfilePreviewMarkdown_rulesSectionListsDiskFiles() throws Exception {
            Files.createDirectories(ccxConfigDir.resolve("profiles/java/rules"));
            Files.writeString(ccxConfigDir.resolve("profiles/java/rules/java-safety.md"), "# Java safety");
            Files.writeString(ccxConfigDir.resolve("profiles/java/rules/git.md"), "# Git rules");

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.profiles())
                .anySatisfy(p -> {
                    assertThat(p.name()).isEqualTo("java");
                    var preview = p.previewMarkdown();
                    var rulesH3Start = preview.indexOf("### Rules");
                    assertThat(rulesH3Start)
                        .as("preview must contain a '### Rules' section")
                        .isGreaterThanOrEqualTo(0);
                    var rulesSectionText = preview.substring(rulesH3Start);
                    // Bug: current code reads profile.rules.files (YAML model) → shows YAML values
                    // Fix: must read from profileDir/rules/ dir and show actual filenames
                    assertThat(rulesSectionText)
                        .as("Rules section must list java-safety.md from disk")
                        .contains("java-safety.md");
                });
        }

        @Test
        @DisplayName("no separate bold 'Rule files' section when rules/ dir files are already in the H3 section")
        void buildProfilePreviewMarkdown_noSeparateRuleFilesBoldSection() throws Exception {
            Files.createDirectories(ccxConfigDir.resolve("profiles/java/rules"));
            Files.writeString(ccxConfigDir.resolve("profiles/java/rules/java.md"), "# Java rules");

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.profiles())
                .anySatisfy(p -> {
                    assertThat(p.name()).isEqualTo("java");
                    var preview = p.previewMarkdown();
                    assertThat(preview)
                        .as("bold 'Rule files' section must not appear as a separate section (duplicate of Rules H3)")
                        .doesNotContain("**Rule files**");
                });
        }
    }

    // -----------------------------------------------------------------------
    // Wave 10 TDD — buildStackEntries() result sorted alphabetically
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when stack entries are sorted")
    class WhenStackEntriesSorted {

        @Test
        @DisplayName("claudeMdEntries from multiple profile claudemd/ dirs are sorted alphabetically by name")
        void buildStackEntries_multipleFragments_sortedByName() throws Exception {
            // Create fragments with names that won't naturally sort in creation order
            Files.createDirectories(ccxConfigDir.resolve("profiles/java/claudemd"));
            Files.writeString(ccxConfigDir.resolve("profiles/java/claudemd/zulu.md"), "# zulu");
            Files.writeString(ccxConfigDir.resolve("profiles/java/claudemd/alpha.md"), "# alpha");
            Files.writeString(ccxConfigDir.resolve("profiles/java/claudemd/mango.md"), "# mango");

            var snapshot = service.snapshot(projectDir, globalRoot);

            var names = snapshot.claudeMdEntries().stream()
                .map(TuiSnapshot.StackEntry::name)
                .toList();

            assertThat(names)
                .as("claudeMdEntries must be sorted alphabetically by name")
                .isSortedAccordingTo(String::compareTo);
        }

        @Test
        @DisplayName("toggling (adding then removing a marker block entry) does not change relative sort order")
        void buildStackEntries_sortOrderStableRegardlessOfMarkerBlock() throws Exception {
            Files.createDirectories(ccxConfigDir.resolve("profiles/java/claudemd"));
            var fragFile = ccxConfigDir.resolve("profiles/java/claudemd/beta.md");
            var fragFile2 = ccxConfigDir.resolve("profiles/java/claudemd/alpha.md");
            Files.writeString(fragFile, "# beta");
            Files.writeString(fragFile2, "# alpha");

            // Snapshot without any marker block — fragments appear as AVAILABLE
            var snapBefore = service.snapshot(projectDir, globalRoot);
            var namesBefore = snapBefore.claudeMdEntries().stream()
                .map(TuiSnapshot.StackEntry::name)
                .toList();

            // Add beta to the marker block (simulating toggling it on)
            writeClaudeMd("<!-- ccx:begin -->\n@" + fragFile.toAbsolutePath().normalize() + "\n<!-- ccx:end -->\n");

            var snapAfter = service.snapshot(projectDir, globalRoot);
            var namesAfter = snapAfter.claudeMdEntries().stream()
                .map(TuiSnapshot.StackEntry::name)
                .toList();

            assertThat(namesAfter)
                .as("entries must remain sorted alphabetically after toggling one entry into the marker block")
                .isSortedAccordingTo(String::compareTo);

            // Both before and after must be sorted
            assertThat(namesBefore)
                .as("entries must be sorted alphabetically before toggling")
                .isSortedAccordingTo(String::compareTo);
        }
    }

    // -----------------------------------------------------------------------
    // Plan-031 TDD — Task 1: rules section lists only .md files
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when profile rules/ dir contains mixed file types")
    class WhenRulesFilterInPreview {

        @Test
        @DisplayName("Rules section in preview must not list .json files")
        void buildProfilePreviewMarkdown_rulesSection_excludesJsonFiles() throws Exception {
            var rulesDir = ccxConfigDir.resolve("profiles/java/rules");
            Files.createDirectories(rulesDir);
            Files.writeString(rulesDir.resolve("java-safety.md"), "# Java safety rules");
            Files.writeString(rulesDir.resolve("metadata.json"), "{\"version\":1}");

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.profiles())
                .anySatisfy(p -> {
                    assertThat(p.name()).isEqualTo("java");
                    var preview = p.previewMarkdown();
                    var rulesH3Start = preview.indexOf("### Rules");
                    assertThat(rulesH3Start)
                        .as("preview must contain a '### Rules' section")
                        .isGreaterThanOrEqualTo(0);
                    var rulesSection = preview.substring(rulesH3Start);
                    assertThat(rulesSection)
                        .as("Rules section must NOT list .json files")
                        .doesNotContain("metadata.json");
                    assertThat(rulesSection)
                        .as("Rules section must still list .md files")
                        .contains("java-safety.md");
                });
        }

        @Test
        @DisplayName("Rules section lists only .md filenames when both .md and .json files exist")
        void buildProfilePreviewMarkdown_rulesSection_containsOnlyMdFilenames() throws Exception {
            var rulesDir = ccxConfigDir.resolve("profiles/java/rules");
            Files.createDirectories(rulesDir);
            Files.writeString(rulesDir.resolve("security.md"), "# Security rules");
            Files.writeString(rulesDir.resolve("config.json"), "{\"key\":\"value\"}");
            Files.writeString(rulesDir.resolve("index.json"), "{}");

            var snapshot = service.snapshot(projectDir);

            assertThat(snapshot.profiles())
                .anySatisfy(p -> {
                    assertThat(p.name()).isEqualTo("java");
                    var preview = p.previewMarkdown();
                    assertThat(preview)
                        .as("preview must not contain config.json from rules dir")
                        .doesNotContain("config.json");
                    assertThat(preview)
                        .as("preview must not contain index.json from rules dir")
                        .doesNotContain("index.json");
                });
        }
    }

    // -----------------------------------------------------------------------
    // Plan-032 Wave 4 — F43: 3-arg ctor must not mutate the caller's ObjectMapper
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Plan 040 Item 31 — fragmentNames integration: profile claudemd fragments
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Plan 040 — overview.fragmentNames includes profile claudemd fragments when referenced")
    class WhenFragmentNamesIncludesProfileClaudeMdFragments {

        @Test
        @DisplayName("fragmentNames includes the name of a profile claudemd fragment when it is referenced in the marker block")
        void fragmentNames_includesProfileClaudeMdFragments() throws Exception {
            // Create a profile's claudemd directory with a fragment file
            var profileClaudeMdDir = ccxConfigDir.resolve("profiles/java/claudemd");
            Files.createDirectories(profileClaudeMdDir);
            var fragmentFile = profileClaudeMdDir.resolve("my-fragment.md");
            Files.writeString(fragmentFile, "# My fragment content");

            // Reference the fragment in the CLAUDE.md marker block (makes it PROJECT-sourced)
            var absPath = fragmentFile.toAbsolutePath().normalize().toString();
            writeClaudeMd("<!-- ccx:begin -->\n@" + absPath + "\n<!-- ccx:end -->\n");

            var snap = service.snapshot(projectDir, globalRoot);

            // The fragment must appear in the claudeMdEntries as PROJECT source
            assertThat(snap.claudeMdEntries())
                .as("the referenced profile claudemd fragment must appear as PROJECT source")
                .anySatisfy(e -> {
                    assertThat(e.name()).isEqualTo("my-fragment");
                    assertThat(e.source()).isEqualTo(TuiSnapshot.StackSource.PROJECT);
                });

            // And must be included in fragmentNames (which includes PROJECT and FRAGMENT sources)
            assertThat(snap.overview().fragmentNames())
                .as("fragmentNames must include the name of the referenced profile claudemd fragment")
                .contains("my-fragment");
        }
    }

    @Nested
    @DisplayName("when constructing with an external ObjectMapper")
    class WhenConstructedWithExternalMapper {

        @Test
        @DisplayName("3-arg ctor must not register modules on the caller's mapper")
        void threeArgCtor_doesNotMutateCallerMapper() {
            var caller = new ObjectMapper();
            var before = caller.getRegisteredModuleIds();

            new TuiDataService(settingsService, new ClaudeMdService(), caller);

            assertThat(caller.getRegisteredModuleIds())
                .as("the caller's mapper must not gain modules when passed to the 3-arg ctor")
                .isEqualTo(before);
        }
    }

}
