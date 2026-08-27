// Wave 4 — Stubs + Failing Tests
package me.neveux.ccx.cli;

import me.neveux.ccx.model.MarketplaceSource;
import me.neveux.ccx.model.Settings;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.ClaudePluginInstallService;
import me.neveux.ccx.service.ClaudePluginRegistryService;
import me.neveux.ccx.service.MarketplaceCloneService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.ProfileService;
import me.neveux.ccx.service.SettingsService;

import java.io.IOException;
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

@DisplayName("PluginsCommand")
class PluginsCommandTest {

    @TempDir
    Path projectDir;

    @TempDir
    Path globalDir;

    private SettingsService settingsService;
    private CommandLine commandLine;
    private final ByteArrayOutputStream outputCapture = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        settingsService = new SettingsService();
        var factory = new TestCommandFactory(
            settingsService, new ProfileService(), new PluginMapService(),
            new ClaudeMdService()
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

    /** Creates a fake marketplace directory with a plugin subdirectory and wires it into project settings. */
    private Path createMarketplace(String marketplaceKey, String... pluginNames) throws Exception {
        var marketplaceDir = projectDir.resolve("marketplace-" + marketplaceKey);
        Files.createDirectories(marketplaceDir);
        for (var name : pluginNames) {
            Files.createDirectories(marketplaceDir.resolve(name));
        }

        var source = new MarketplaceSource(marketplaceKey, marketplaceDir.toString(), null, null);

        var settings = settingsService.read(projectDir).withExtraKnownMarketplaces(List.of(source));
        settingsService.write(settings, projectDir);

        return marketplaceDir;
    }

    @Nested
    @DisplayName("plugins list")
    class List_ {

        @Test
        @DisplayName("should show green indicator for a plugin enabled in project settings")
        void list_showsGreenForProjectEnabledPlugin() throws Exception {
            createMarketplace("my-mkt", "java");
            var settings = settingsService.read(projectDir);
            var enabled = settings.enabledPlugins() == null
                ? new java.util.LinkedHashMap<String, Boolean>()
                : new java.util.LinkedHashMap<>(settings.enabledPlugins());
            enabled.put("java@my-mkt", true);
            settings = settings.withEnabledPlugins(enabled);
            settingsService.write(settings, projectDir);

            run("plugins", "list",
                "--project-dir", projectDir.toString(),
                "--global-dir", globalDir.toString());

            assertThat(output())
                .as("project-enabled plugin must show the project (💚) emoji, not a geometric glyph")
                .contains("💚")
                .doesNotContain("●")
                .contains("java@my-mkt");
        }

        @Test
        @DisplayName("should show blue indicator for a plugin enabled only in global settings")
        void list_showsBlueForGlobalOnlyPlugin() throws Exception {
            // global settings have the plugin; project settings do not
            var globalSettings = new Settings().withEnabledPlugins(Map.of("shared-plugin@global-mkt", true));
            settingsService.write(globalSettings, globalDir);

            run("plugins", "list",
                "--project-dir", projectDir.toString(),
                "--global-dir", globalDir.toString());

            assertThat(output())
                .as("global-only plugin must show the global (💙) emoji, not a geometric glyph")
                .contains("💙")
                .doesNotContain("◎")
                .contains("shared-plugin@global-mkt");
        }

        @Test
        @DisplayName("should show available indicator for a plugin registered only in global marketplace")
        void list_showsAvailableForGlobalOnlyMarketplacePlugin() throws Exception {
            var globalMarketplaceDir = globalDir.resolve("some-global-mkt");
            Files.createDirectories(globalMarketplaceDir.resolve("global-only-plugin"));

            var source = new MarketplaceSource("global-mkt", globalMarketplaceDir.toString(), null, null);

            var globalSettings = new Settings().withExtraKnownMarketplaces(List.of(source));
            settingsService.write(globalSettings, globalDir);

            // project settings have NO extraKnownMarketplaces
            run("plugins", "list",
                "--project-dir", projectDir.toString(),
                "--global-dir", globalDir.toString());

            assertThat(output())
                .as("plugin from global-only marketplace must appear with the available (🤍) emoji, not a geometric glyph")
                .contains("🤍")
                .doesNotContain("·");
            assertThat(output())
                .as("plugin from global-only marketplace must show its qualified name")
                .contains("global-only-plugin");
        }

        @Test
        @DisplayName("should show white indicator for a discovered but unenabled plugin")
        void list_showsWhiteForUnenabledPlugin() throws Exception {
            createMarketplace("my-mkt", "unenabled-plugin");
            // no enabledPlugins entry

            run("plugins", "list",
                "--project-dir", projectDir.toString(),
                "--global-dir", globalDir.toString());

            assertThat(output())
                .as("unenabled plugin must show the available (🤍) emoji, not a geometric glyph")
                .contains("🤍")
                .doesNotContain("·")
                .contains("unenabled-plugin");
        }
    }

    @Nested
    @DisplayName("plugins enable")
    class Enable {

        @Test
        @DisplayName("should write qualified name (shortName@key) when plugin is in the marketplace map")
        void enable_writesQualifiedNameToSettings() throws Exception {
            createMarketplace("my-mkt", "java");

            run("plugins", "enable", "java",
                "--project-dir", projectDir.toString());

            var written = settingsService.read(projectDir);
            assertThat(written.enabledPlugins())
                .as("enable must add the qualified plugin key to enabledPlugins")
                .containsKey("java@my-mkt");
            assertThat(written.enabledPlugins().get("java@my-mkt"))
                .as("enable must set the plugin value to true")
                .isTrue();
        }

        @Test
        @DisplayName("should use the raw name when the marketplace map has no match")
        void enable_usesShortNameWhenNotInMap() throws Exception {
            // no marketplace configured → no map → name is used as-is

            run("plugins", "enable", "foo",
                "--project-dir", projectDir.toString());

            var written = settingsService.read(projectDir);
            assertThat(written.enabledPlugins())
                .as("enable without map match must use the raw name as key")
                .containsKey("foo");
        }
    }

    /** Reports a fixed set of plugins as missing an installation record for the project. */
    static class StubRegistryService extends ClaudePluginRegistryService {
        List<String> missing = List.of();

        @Override
        public boolean isInstalledFor(String qualifiedName, Path projectRoot) {
            return !missing.contains(qualifiedName);
        }

        @Override
        public List<String> missingInstalls(Map<String, Boolean> enabledPlugins, Path projectRoot) {
            return missing;
        }
    }

    /** Records install attempts instead of spawning the Claude Code CLI. */
    static class RecordingInstallService extends ClaudePluginInstallService {
        final java.util.List<String> installed = new java.util.ArrayList<>();
        InstallResult result = new InstallResult(Status.INSTALLED, "installed");

        @Override
        public InstallResult install(String qualifiedName, Path projectRoot) {
            installed.add(qualifiedName);
            return result;
        }
    }

    private CommandLine commandLineWith(ClaudePluginRegistryService registryService,
                                       ClaudePluginInstallService installService) {
        var factory = new TestCommandFactory(
            settingsService, new ProfileService(), new PluginMapService(), new ClaudeMdService())
            .withClaudePluginRegistryService(registryService)
            .withClaudePluginInstallService(installService);
        var cl = new CommandLine(new CcxCommand(), factory);
        cl.setOut(new PrintWriter(outputCapture, true));
        cl.setErr(new PrintWriter(outputCapture, true));
        return cl;
    }

    @Nested
    @DisplayName("plugins enable — installation")
    class EnableInstall {

        @Test
        @DisplayName("should install the plugin before writing settings when it has no install record")
        void enable_installsMissingPlugin() throws Exception {
            var registry = new StubRegistryService();
            registry.missing = List.of("foo");
            var install = new RecordingInstallService();
            outputCapture.reset();

            int exit = commandLineWith(registry, install).execute("plugins", "enable", "foo",
                "--project-dir", projectDir.toString());

            assertThat(install.installed)
                .as("an enabled plugin with no install record must be installed for the project")
                .containsExactly("foo");
            assertThat(output())
                .as("enable must report the install it performed")
                .contains("Installed: foo");
            assertThat(settingsService.read(projectDir).enabledPlugins())
                .as("enable must still write the enablement")
                .containsKey("foo");
            assertThat(exit).as("a successful enable must exit 0").isZero();
        }

        @Test
        @DisplayName("should skip the install when the plugin is already installed for the project")
        void enable_skipsInstallWhenAlreadyInstalled() {
            var registry = new StubRegistryService();
            var install = new RecordingInstallService();
            outputCapture.reset();

            commandLineWith(registry, install).execute("plugins", "enable", "foo",
                "--project-dir", projectDir.toString());

            assertThat(install.installed)
                .as("an already-installed plugin must not be reinstalled")
                .isEmpty();
            assertThat(output())
                .as("no install happened, so no install line must be printed")
                .doesNotContain("Installed:");
        }

        @Test
        @DisplayName("should warn but still enable and exit 0 when the install fails")
        void enable_warnsAndStillEnablesOnInstallFailure() throws Exception {
            var registry = new StubRegistryService();
            registry.missing = List.of("foo");
            var install = new RecordingInstallService();
            install.result = new ClaudePluginInstallService.InstallResult(
                ClaudePluginInstallService.Status.CLI_NOT_FOUND, "no Claude Code CLI on PATH");
            outputCapture.reset();

            int exit = commandLineWith(registry, install).execute("plugins", "enable", "foo",
                "--project-dir", projectDir.toString());

            assertThat(output())
                .as("a failed install must be surfaced with the underlying reason")
                .contains("Warning: could not install 'foo'")
                .contains("no Claude Code CLI on PATH");
            assertThat(settingsService.read(projectDir).enabledPlugins())
                .as("a failed install must not block the enablement — the state stays repairable")
                .containsKey("foo");
            assertThat(exit)
                .as("a failed install is a warning, not a failure, on the enable path")
                .isZero();
        }
    }

    @Nested
    @DisplayName("plugins sync")
    class Sync {

        @Test
        @DisplayName("should install every enabled plugin that has no install record")
        void sync_installsMissingPlugins() throws Exception {
            settingsService.write(
                new Settings().withEnabledPlugins(Map.of("slack@mkt", true)), projectDir);
            var registry = new StubRegistryService();
            registry.missing = List.of("slack@mkt");
            var install = new RecordingInstallService();
            outputCapture.reset();

            int exit = commandLineWith(registry, install).execute("plugins", "sync",
                "--project-dir", projectDir.toString());

            assertThat(install.installed)
                .as("sync must install each plugin missing an install record")
                .containsExactly("slack@mkt");
            assertThat(output())
                .as("sync must confirm each install")
                .contains("Installed slack@mkt");
            assertThat(exit).as("an all-successful sync must exit 0").isZero();
        }

        @Test
        @DisplayName("should report nothing to do and exit 0 when every plugin is installed")
        void sync_reportsNothingToDo() throws Exception {
            settingsService.write(
                new Settings().withEnabledPlugins(Map.of("slack@mkt", true)), projectDir);
            var install = new RecordingInstallService();
            outputCapture.reset();

            int exit = commandLineWith(new StubRegistryService(), install).execute("plugins", "sync",
                "--project-dir", projectDir.toString());

            assertThat(output())
                .as("sync with nothing missing must say so")
                .contains("All enabled plugins are installed for this project");
            assertThat(install.installed)
                .as("sync must not install anything when nothing is missing")
                .isEmpty();
            assertThat(exit).as("a no-op sync must exit 0").isZero();
        }

        @Test
        @DisplayName("--dry-run should list what would be installed without installing")
        void sync_dryRunInstallsNothing() throws Exception {
            settingsService.write(
                new Settings().withEnabledPlugins(Map.of("slack@mkt", true)), projectDir);
            var registry = new StubRegistryService();
            registry.missing = List.of("slack@mkt");
            var install = new RecordingInstallService();
            outputCapture.reset();

            int exit = commandLineWith(registry, install).execute("plugins", "sync", "--dry-run",
                "--project-dir", projectDir.toString());

            assertThat(output())
                .as("--dry-run must report the plugin it would install")
                .contains("would install slack@mkt");
            assertThat(install.installed)
                .as("--dry-run must never install")
                .isEmpty();
            assertThat(exit).as("--dry-run must exit 0").isZero();
        }

        @Test
        @DisplayName("should exit 1 and name the plugin when an install fails")
        void sync_exitsOneOnInstallFailure() throws Exception {
            settingsService.write(
                new Settings().withEnabledPlugins(Map.of("slack@mkt", true)), projectDir);
            var registry = new StubRegistryService();
            registry.missing = List.of("slack@mkt");
            var install = new RecordingInstallService();
            install.result = new ClaudePluginInstallService.InstallResult(
                ClaudePluginInstallService.Status.FAILED, "exit=1 marketplace not found");
            outputCapture.reset();

            int exit = commandLineWith(registry, install).execute("plugins", "sync",
                "--project-dir", projectDir.toString());

            assertThat(output())
                .as("a failed repair must name the plugin and the reason")
                .contains("Failed to install slack@mkt")
                .contains("marketplace not found");
            assertThat(exit)
                .as("sync is an explicit repair command — a failure must be visible in the exit code")
                .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("mutating subcommands with a corrupt settings.json")
    class WhenSettingsJsonIsCorrupt {

        private static final String CORRUPT =
            "{\"model\":\"claude-opus-4-8\",\"enabledPlugins\":{\"java@my-mkt\":true}";

        private Path writeCorruptSettings() throws Exception {
            var settingsFile = projectDir.resolve(".claude/settings.json");
            Files.createDirectories(settingsFile.getParent());
            Files.writeString(settingsFile, CORRUPT);
            return settingsFile;
        }

        @Test
        @DisplayName("enable exits non-zero, reports the file, and leaves it byte-identical")
        void enable_corruptSettings_refusesAndPreservesFile() throws Exception {
            var settingsFile = writeCorruptSettings();

            int exit = run("plugins", "enable", "java", "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("silently rewriting the file would destroy the user's model/statusLine keys")
                .isNotZero();
            assertThat(output())
                .as("the diagnostic must name the file the user has to repair")
                .contains("Refusing to modify")
                .contains(settingsFile.toString());
            assertThat(settingsFile).hasContent(CORRUPT);
        }

        @Test
        @DisplayName("disable exits non-zero, reports the file, and leaves it byte-identical")
        void disable_corruptSettings_refusesAndPreservesFile() throws Exception {
            var settingsFile = writeCorruptSettings();

            int exit = run("plugins", "disable", "java@my-mkt", "--project-dir", projectDir.toString());

            assertThat(exit).isNotZero();
            assertThat(output())
                .contains("Refusing to modify")
                .contains(settingsFile.toString());
            assertThat(settingsFile).hasContent(CORRUPT);
        }

        @Test
        @DisplayName("list still works on a corrupt file — read-only paths stay lenient")
        void list_corruptSettings_stillReports() throws Exception {
            writeCorruptSettings();

            int exit = run("plugins", "list", "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("a read-only view must degrade rather than fail: nothing is at risk of being written")
                .isZero();
        }
    }

    @Nested
    @DisplayName("plugins enable — security")
    class EnableSecurity {

        @Test
        @DisplayName("should reject --project-dir pointing to /etc")
        void enable_rejectsProjectDirOutsideAllowedRoots() {
            run("plugins", "enable", "java", "--project-dir", "/etc");

            assertThat(output())
                .as("enable with --project-dir outside allowed roots must report 'outside allowed' (F22)")
                .containsIgnoringCase("outside allowed");
        }
    }

    @Nested
    @DisplayName("plugins disable")
    class Disable {

        @Test
        @DisplayName("should remove the plugin key from project settings")
        void disable_removesPluginFromSettings() throws Exception {
            var settings = new Settings().withEnabledPlugins(new java.util.LinkedHashMap<>(Map.of("java@my-mkt", true)));
            settingsService.write(settings, projectDir);

            run("plugins", "disable", "java@my-mkt",
                "--project-dir", projectDir.toString());

            var written = settingsService.read(projectDir);
            assertThat(written.enabledPlugins())
                .as("disable must remove the plugin key from enabledPlugins")
                .doesNotContainKey("java@my-mkt");
        }

        @Test
        @DisplayName("should reject --project-dir pointing to /etc, exit 1, and not write any file")
        void disable_rejectsTraversalProjectDir_doesNotWrite() {
            int exit = run("plugins", "disable", "someplugin",
                "--project-dir", "/etc");

            assertThat(exit)
                .as("disable with /etc --project-dir must exit 1")
                .isEqualTo(1);
            assertThat(output())
                .as("disable with /etc --project-dir must report an error mentioning 'outside allowed'")
                .containsIgnoringCase("outside allowed");
        }
    }

    @Nested
    @DisplayName("plugins tags")
    class Tags {

        @Test
        @DisplayName("should print 'No tags found' message when no marketplaces are configured")
        void tags_printsNoTagsFound_whenNoMarketplacesConfigured() {
            run("plugins", "tags",
                "--project-dir", projectDir.toString(),
                "--global-dir", globalDir.toString());

            assertThat(output())
                .as("tags with no marketplace sources must report that no tags were found")
                .containsIgnoringCase("No tags");
        }

        @Test
        @DisplayName("should print marketplace key when a marketplace source is configured with that key")
        void tags_printsTags_whenPluginsHaveAtSuffix() throws Exception {
            createMarketplace("mymarketplace", "java-ai");

            run("plugins", "tags",
                "--project-dir", projectDir.toString(),
                "--global-dir", globalDir.toString());

            assertThat(output())
                .as("tags must print the marketplace source key")
                .contains("mymarketplace");
        }
    }

    // -----------------------------------------------------------------------
    // Wave 4 — F19: RefreshCommand.call()
    // -----------------------------------------------------------------------

    /** Records refresh invocations and can be configured to throw. */
    static class RecordingCloneService extends MarketplaceCloneService {
        final java.util.List<String> refreshed = new java.util.ArrayList<>();
        boolean throwOnRefresh = false;

        @Override
        public void refresh(MarketplaceSource source) throws IOException {
            if (throwOnRefresh) {
                throw new IOException("simulated git failure for " + source.key());
            }
            refreshed.add(source.key());
        }
    }

    /**
     * Writes a github marketplace source as raw settings.json. The production serializer is
     * lossy (it hardcodes sourceType=directory and drops githubRepo), so to exercise the
     * github code path we write the CCX array format the deserializer understands directly.
     */
    private void writeGithubSource(Path settingsRoot, String key) throws Exception {
        var claudeDir = settingsRoot.resolve(".claude");
        Files.createDirectories(claudeDir);
        var json = "{\"extraKnownMarketplaces\":[{\"key\":\"" + key
            + "\",\"sourceType\":\"github\",\"githubRepo\":\"owner/" + key + "\"}]}";
        Files.writeString(claudeDir.resolve("settings.json"), json);
    }

    private CommandLine commandLineWith(MarketplaceCloneService cloneService) {
        var factory = new TestCommandFactory(
            settingsService, new ProfileService(), new PluginMapService(),
            new ClaudeMdService(), cloneService
        );
        var cl = new CommandLine(new CcxCommand(), factory);
        cl.setOut(new PrintWriter(outputCapture, true));
        cl.setErr(new PrintWriter(outputCapture, true));
        return cl;
    }

    @Nested
    @DisplayName("plugins refresh")
    class Refresh {

        @Test
        @DisplayName("refresh of a github source succeeds and confirms in output")
        void refresh_happyPath_confirmsSuccess() throws Exception {
            writeGithubSource(projectDir, "remote-mkt");
            var clone = new RecordingCloneService();
            outputCapture.reset();

            int exit = commandLineWith(clone).execute("plugins", "refresh",
                "--project-dir", projectDir.toString(),
                "--global-dir", globalDir.toString());

            assertThat(exit)
                .as("refresh with a working clone service must exit 0")
                .isZero();
            assertThat(clone.refreshed)
                .as("the github marketplace must have been refreshed")
                .containsExactly("remote-mkt");
            assertThat(output())
                .as("refresh must confirm the marketplace it refreshed")
                .contains("remote-mkt");
        }

        @Test
        @DisplayName("refresh with no marketplace sources is graceful (exit 0, no failures)")
        void refresh_noSources_isGraceful() {
            var clone = new RecordingCloneService();
            outputCapture.reset();

            int exit = commandLineWith(clone).execute("plugins", "refresh",
                "--project-dir", projectDir.toString(),
                "--global-dir", globalDir.toString());

            assertThat(exit)
                .as("refresh with no marketplaces must exit 0 (nothing to do)")
                .isZero();
            assertThat(clone.refreshed)
                .as("no marketplaces means nothing to refresh")
                .isEmpty();
        }

        @Test
        @DisplayName("a refresh failure is surfaced and yields a non-zero exit")
        void refresh_failure_isSurfaced() throws Exception {
            writeGithubSource(projectDir, "remote-mkt");
            var clone = new RecordingCloneService();
            clone.throwOnRefresh = true;
            outputCapture.reset();

            int exit = commandLineWith(clone).execute("plugins", "refresh",
                "--project-dir", projectDir.toString(),
                "--global-dir", globalDir.toString());

            assertThat(exit)
                .as("a refresh failure must produce a non-zero exit code")
                .isNotZero();
            assertThat(output())
                .as("the failed marketplace must be named in the error output")
                .contains("remote-mkt");
        }
    }

    @Nested
    @DisplayName("plugins diff")
    class Diff {

        @Test
        @DisplayName("should print Project-only section when a plugin exists in project but not global")
        void diff_showsProjectOnlySection() throws Exception {
            var projectSettings = new Settings().withEnabledPlugins(Map.of("project-exclusive@mkt", true));
            settingsService.write(projectSettings, projectDir);

            run("plugins", "diff",
                "--project-dir", projectDir.toString(),
                "--global-dir", globalDir.toString());

            assertThat(output())
                .as("diff must show Project-only: section")
                .contains("Project-only:")
                .contains("project-exclusive@mkt");
        }

        @Test
        @DisplayName("should print Global-only section when a plugin exists in global but not project")
        void diff_showsGlobalOnlySection() throws Exception {
            var globalSettings = new Settings().withEnabledPlugins(Map.of("global-exclusive@mkt", true));
            settingsService.write(globalSettings, globalDir);

            run("plugins", "diff",
                "--project-dir", projectDir.toString(),
                "--global-dir", globalDir.toString());

            assertThat(output())
                .as("diff must show Global-only: section")
                .contains("Global-only:")
                .contains("global-exclusive@mkt");
        }

        @Test
        @DisplayName("should print Both section when a plugin exists in both project and global settings")
        void diff_showsBothSection() throws Exception {
            var projectSettings = new Settings().withEnabledPlugins(Map.of("shared@mkt", true));
            settingsService.write(projectSettings, projectDir);

            var globalSettings = new Settings().withEnabledPlugins(Map.of("shared@mkt", true));
            settingsService.write(globalSettings, globalDir);

            run("plugins", "diff",
                "--project-dir", projectDir.toString(),
                "--global-dir", globalDir.toString());

            assertThat(output())
                .as("diff must show Both: section for plugin present in both")
                .contains("Both:")
                .contains("shared@mkt");
        }
    }
}
