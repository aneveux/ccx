package me.neveux.ccx.cli;

import me.neveux.ccx.model.Settings;
import me.neveux.ccx.service.ClaudePluginInstallService;
import me.neveux.ccx.service.ClaudePluginRegistryService;
import me.neveux.ccx.service.MarketplaceCloneService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.SettingsService;
import me.neveux.ccx.tui.Quips;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * Manages Claude Code plugin entries in {@code .claude/settings.json}. Plugins
 * are identified by their qualified marketplace key (e.g. {@code java:java@acme}).
 * Subcommands cover listing (with enabled/global/available status), enabling,
 * disabling, installing what is enabled but missing, comparing project vs global
 * state, browsing tags, and force-refreshing the marketplace clone.
 */
@Command(
    name = "plugins",
    description = "Manage plugins",
    subcommands = {
        PluginsCommand.ListCommand.class,
        PluginsCommand.EnableCommand.class,
        PluginsCommand.DisableCommand.class,
        PluginsCommand.SyncCommand.class,
        PluginsCommand.DiffCommand.class,
        PluginsCommand.TagsCommand.class,
        PluginsCommand.RefreshCommand.class,
        CommandLine.HelpCommand.class
    },
    mixinStandardHelpOptions = true
)
@Dependent
@Unremovable
@RegisterForReflection
public class PluginsCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    /**
     * Installs every plugin enabled in {@code dir} that Claude Code has no installation record
     * for, printing one line per plugin. Shared by {@code plugins sync} and the profile-enable
     * paths, which all leave {@code enabledPlugins} entries behind that Claude Code cannot load
     * until the matching install exists.
     *
     * @param registryService  reads Claude Code's installation records
     * @param installService   runs the Claude Code CLI
     * @param settings         the project's settings, already read
     * @param dir              the project directory
     * @param spec             command spec, for the out/err writers
     * @param failureIsFatal   whether the caller exits non-zero on a failed install; controls
     *                         whether failures read as errors or as warnings
     * @return the number of plugins that could not be installed
     */
    static int installMissingPlugins(ClaudePluginRegistryService registryService,
                                     ClaudePluginInstallService installService,
                                     Settings settings, Path dir,
                                     CommandLine.Model.CommandSpec spec,
                                     boolean failureIsFatal) {
        var missing = registryService.missingInstalls(settings.enabledPlugins(), dir);
        if (missing.isEmpty()) return 0;

        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        var failures = 0;
        for (var attempt : installService.installAll(missing, dir).entrySet()) {
            var plugin = attempt.getKey();
            var result = attempt.getValue();
            if (result.isSuccess()) {
                out.println(CliFormat.OK + " Installed " + plugin);
            } else {
                err.println(failureIsFatal
                    ? CliFormat.ERR + " Failed to install " + plugin + ": " + result.message()
                    : CliFormat.WARN + " Could not install " + plugin + ": " + result.message());
                failures++;
            }
        }
        out.flush();
        return failures;
    }

    /**
     * Returns the set of plugin keys declared in {@code settings.enabledPlugins()},
     * preserving insertion order. Returns an empty set when no plugins are configured.
     */
    static Set<String> pluginKeys(Settings settings) {
        if (settings.enabledPlugins() == null) return new LinkedHashSet<>();
        return new LinkedHashSet<>(settings.enabledPlugins().keySet());
    }

    /**
     * Lists all known plugins from the marketplace merged with project and global
     * settings. Each entry is prefixed with a scope glyph: enabled in project
     * ({@link CliFormat#ENABLED}), enabled globally ({@link CliFormat#GLOBAL}),
     * or available but not enabled ({@link CliFormat#AVAILABLE}). Supports
     * filtering by tag via {@code --tag}.
     *
     * <p>Returns 1 when a supplied path is rejected, so a caller can tell a rejection apart from
     * a project with no plugins enabled; 0 otherwise.
     */
    @Command(name = "list", description = "List all known plugins with their enabled status")
    @RegisterForReflection
    static class ListCommand implements Callable<Integer> {

        @Inject
        SettingsService settingsService;

        @Inject
        PluginMapService pluginMapService;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @Option(names = {"--global-dir"}, description = "Global Claude config directory (defaults to ~/.claude)")
        Path globalDir;

        @Option(names = {"--tag", "-t"}, description = "Filter by tag (repeatable, AND logic)")
        List<String> tags;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var projDir = CommandUtil.resolveProjectDir(projectDir);
            var globDir = CommandUtil.resolveGlobalDir(globalDir);
            if (!CommandUtil.checkPathSecurity(projDir, spec)) return 1;
            if (!CommandUtil.checkPathSecurity(globDir, spec, "--global-dir")) return 1;

            var projectSettings = settingsService.read(projDir);
            var globalSettings = settingsService.read(globDir);

            var projectPlugins = pluginKeys(projectSettings);
            var globalPlugins = pluginKeys(globalSettings);

            // All known plugins from marketplace scan (project + global sources merged)
            var marketplacePlugins = new LinkedHashSet<>(pluginMapService.buildMap(projectSettings, globalSettings).entries().values());

            var all = new TreeSet<String>();
            all.addAll(projectPlugins);
            all.addAll(globalPlugins);
            all.addAll(marketplacePlugins);

            var out = spec.commandLine().getOut();
            for (var plugin : all.stream().filter(key -> tags == null || tags.isEmpty() || tags.stream().allMatch(t -> key.contains(t))).toList()) {
                String icon;
                if (projectPlugins.contains(plugin)) {
                    icon = CliFormat.ENABLED;
                } else if (globalPlugins.contains(plugin)) {
                    icon = CliFormat.GLOBAL;
                } else {
                    icon = CliFormat.AVAILABLE;
                }
                out.println(icon + " " + plugin);
            }
            out.flush();
            return 0;
        }

    }

    /**
     * Enables a plugin in the project's {@code settings.json} and installs it for that project
     * so Claude Code can actually load it. Resolves short names to fully-qualified marketplace
     * keys via the plugin map; warns and uses the raw name when resolution fails.
     *
     * <p>A failed install never blocks the enablement: the entry is written anyway and the
     * manual remediation command is printed, leaving the state repairable with
     * {@code ccx plugins sync}.
     */
    @Command(name = "enable", description = "Enable a plugin in project settings")
    @RegisterForReflection
    static class EnableCommand implements Callable<Integer> {

        @Inject
        SettingsService settingsService;

        @Inject
        PluginMapService pluginMapService;

        @Inject
        ClaudePluginRegistryService claudePluginRegistryService;

        @Inject
        ClaudePluginInstallService claudePluginInstallService;

        @Parameters(index = "0", description = "Plugin name or short name")
        String name;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var dir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(dir, spec)) return 1;
            var settings = settingsService.readForUpdate(dir);

            var resolved = pluginMapService.resolve(name, dir, settings);
            String qualifiedName;
            if (resolved.isPresent()) {
                qualifiedName = resolved.get();
            } else {
                spec.commandLine().getErr().println(
                    "Warning: plugin '" + name + "' not found in marketplace map — using raw name");
                qualifiedName = name;
            }

            // Install before writing settings: `claude plugin install` rewrites settings.json
            // itself, so ccx must have the last word to preserve its own formatting and
            // round-tripped unknown fields.
            installIfMissing(qualifiedName, dir);

            var enabledPlugins = settings.enabledPlugins() == null
                ? new LinkedHashMap<String, Boolean>()
                : new LinkedHashMap<>(settings.enabledPlugins());
            enabledPlugins.put(qualifiedName, true);
            settingsService.write(settings.withEnabledPlugins(enabledPlugins), dir);
            var out = spec.commandLine().getOut();
            out.println("Enabled: " + qualifiedName);
            out.flush();
            return 0;
        }

        private void installIfMissing(String qualifiedName, Path dir) {
            if (claudePluginRegistryService.isInstalledFor(qualifiedName, dir)) return;
            var out = spec.commandLine().getOut();
            claudePluginInstallService.installAll(List.of(qualifiedName), dir)
                .forEach((plugin, result) -> {
                    if (result.isSuccess()) {
                        out.println("Installed: " + plugin);
                        return;
                    }
                    spec.commandLine().getErr().println(
                        "Warning: could not install '" + plugin + "': " + result.message());
                });
        }
    }

    /**
     * Installs every plugin that is enabled in the project's {@code settings.json} but has no
     * Claude Code installation record for this project — the state Claude Code reports as
     * {@code isn't installed here} or {@code not cached}.
     *
     * <p>This is the repair path for projects configured before installation was automatic, or
     * whose cached plugin tree was garbage-collected. Exits 1 when any install fails, since the
     * whole point of the command is to leave the project loadable.
     */
    @Command(name = "sync", description = "Install enabled plugins that are missing locally")
    @RegisterForReflection
    static class SyncCommand implements Callable<Integer> {

        @Inject
        SettingsService settingsService;

        @Inject
        ClaudePluginRegistryService claudePluginRegistryService;

        @Inject
        ClaudePluginInstallService claudePluginInstallService;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @Option(names = {"--dry-run"}, description = "Report what would be installed without installing")
        boolean dryRun;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var dir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(dir, spec)) return 1;

            var settings = settingsService.read(dir);
            var missing = claudePluginRegistryService.missingInstalls(settings.enabledPlugins(), dir);
            var out = spec.commandLine().getOut();
            if (missing.isEmpty()) {
                out.println(CliFormat.OK + " All enabled plugins are installed for this project");
                out.flush();
                return 0;
            }

            if (dryRun) {
                missing.forEach(plugin -> out.println("would install " + plugin));
                out.flush();
                return 0;
            }

            var failures = installMissingPlugins(
                claudePluginRegistryService, claudePluginInstallService, settings, dir, spec, true);
            return failures == 0 ? 0 : 1;
        }
    }

    /**
     * Removes a plugin from the project's enabled-plugin map. Accepts an exact
     * qualified name or a short name (prefix match on {@code name@}). Exits with
     * code 0 even when no matching plugin is found.
     */
    @Command(name = "disable", description = "Disable a plugin in project settings")
    @RegisterForReflection
    static class DisableCommand implements Callable<Integer> {

        @Inject
        SettingsService settingsService;

        @Parameters(index = "0", description = "Plugin name or qualified key")
        String name;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var projDir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(projDir, spec)) return 1;
            var settings = settingsService.readForUpdate(projDir);

            if (settings.enabledPlugins() == null) {
                spec.commandLine().getOut().println(
                    Quips.EMPTY[0] + " No plugins to disable.");
                return 0;
            }

            // Try exact match first, then prefix match (name + "@")
            var enabledPlugins = new LinkedHashMap<>(settings.enabledPlugins());
            if (enabledPlugins.containsKey(name)) {
                enabledPlugins.remove(name);
            } else {
                var prefix = name + "@";
                enabledPlugins.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
            }

            settingsService.write(settings.withEnabledPlugins(enabledPlugins), projDir);
            var out = spec.commandLine().getOut();
            out.println("Disabled: " + name);
            out.flush();
            return 0;
        }
    }

    /**
     * Compares the project and global plugin sets and prints them in three groups:
     * plugins present in both, project-only, and global-only. Useful for
     * identifying redundancy or gaps between scopes.
     *
     * <p>Returns 1 when a supplied path is rejected, so a caller can tell a rejection apart from
     * two scopes that genuinely share nothing; 0 otherwise.
     */
    @Command(name = "diff", description = "Compare project plugins vs global plugins")
    @RegisterForReflection
    static class DiffCommand implements Callable<Integer> {

        @Inject
        SettingsService settingsService;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @Option(names = {"--global-dir"}, description = "Global Claude config directory (defaults to ~/.claude)")
        Path globalDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var projDir = CommandUtil.resolveProjectDir(projectDir);
            var globDir = CommandUtil.resolveGlobalDir(globalDir);
            if (!CommandUtil.checkPathSecurity(projDir, spec)) return 1;
            if (!CommandUtil.checkPathSecurity(globDir, spec, "--global-dir")) return 1;

            var projectSettings = settingsService.read(projDir);
            var globalSettings = settingsService.read(globDir);

            var projectPlugins = pluginKeys(projectSettings);
            var globalPlugins = pluginKeys(globalSettings);

            var both = new TreeSet<>(projectPlugins);
            both.retainAll(globalPlugins);

            var projectOnly = new TreeSet<>(projectPlugins);
            projectOnly.removeAll(globalPlugins);

            var globalOnly = new TreeSet<>(globalPlugins);
            globalOnly.removeAll(projectPlugins);

            var out = spec.commandLine().getOut();

            if (!both.isEmpty()) {
                out.println("Both:");
                both.forEach(p -> out.println("  " + CliFormat.dim("= " + p)));
            }
            if (!projectOnly.isEmpty()) {
                out.println("Project-only:");
                projectOnly.forEach(p -> out.println("  " + CliFormat.green("+ " + p)));
            }
            if (!globalOnly.isEmpty()) {
                out.println("Global-only:");
                globalOnly.forEach(p -> out.println("  " + CliFormat.yellow("~ " + p)));
            }
            return 0;
        }

    }

    /**
     * Force-fetches (git pull) all configured GitHub marketplace sources, or a
     * single one when {@code --marketplace} is specified. Directory-based sources
     * are skipped. Exits with code 1 if any fetch fails.
     */
    @Command(name = "refresh", description = "Force re-fetch GitHub marketplace sources")
    @RegisterForReflection
    static class RefreshCommand implements Callable<Integer> {

        @Inject
        SettingsService settingsService;

        @Inject
        MarketplaceCloneService marketplaceCloneService;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @Option(names = {"--global-dir"}, description = "Global Claude config directory (defaults to ~/.claude)")
        Path globalDir;

        @Option(names = {"--marketplace", "-m"}, description = "Marketplace key to refresh (default: all)")
        String marketplace;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var projDir = CommandUtil.resolveProjectDir(projectDir);
            var globDir = CommandUtil.resolveGlobalDir(globalDir);
            if (!CommandUtil.checkPathSecurity(projDir, spec)) return 1;
            if (!CommandUtil.checkPathSecurity(globDir, spec, "--global-dir")) return 1;

            var projectSettings = settingsService.read(projDir);
            var globalSettings = settingsService.read(globDir);

            var sources = PluginMapService.mergeSources(projectSettings, globalSettings);

            var out = spec.commandLine().getOut();
            var err = spec.commandLine().getErr();
            int failures = 0;

            for (var source : sources) {
                if (marketplace != null && !marketplace.equals(source.key())) continue;
                if (!"github".equals(source.sourceType())) {
                    out.println("skip " + source.key() + " (directory source)");
                    continue;
                }
                try {
                    marketplaceCloneService.refresh(source);
                    out.println(CliFormat.OK + " Refreshed marketplace '" + source.key() + "'");
                } catch (IOException e) {
                    err.println(CliFormat.ERR + " Failed to refresh marketplace '" + source.key() + "': " + e.getMessage());
                    failures++;
                }
            }

            return failures == 0 ? 0 : 1;
        }
    }

    /**
     * Lists all tag identifiers derived from marketplace source keys and the
     * {@code @source} suffixes of qualified plugin names discovered in both
     * project and global settings.
     *
     * <p>Returns 1 when a supplied path is rejected, so a caller can tell a rejection apart from
     * a configuration with no tags; 0 otherwise.
     */
    @Command(name = "tags", description = "List all available plugin tags")
    @RegisterForReflection
    static class TagsCommand implements Callable<Integer> {

        @Inject
        SettingsService settingsService;

        @Inject
        PluginMapService pluginMapService;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @Option(names = {"--global-dir"}, description = "Global Claude config directory (defaults to ~/.claude)")
        Path globalDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var projDir = CommandUtil.resolveProjectDir(projectDir);
            var globDir = CommandUtil.resolveGlobalDir(globalDir);
            if (!CommandUtil.checkPathSecurity(projDir, spec)) return 1;
            if (!CommandUtil.checkPathSecurity(globDir, spec, "--global-dir")) return 1;

            var projectSettings = settingsService.read(projDir);
            var globalSettings = settingsService.read(globDir);

            var tags = new TreeSet<String>();

            // Collect tags from marketplace source keys (project)
            if (projectSettings.extraKnownMarketplaces() != null) {
                projectSettings.extraKnownMarketplaces().forEach(s -> { if (s.key() != null) tags.add(s.key()); });
            }
            // Collect tags from marketplace source keys (global)
            if (globalSettings.extraKnownMarketplaces() != null) {
                globalSettings.extraKnownMarketplaces().forEach(s -> { if (s.key() != null) tags.add(s.key()); });
            }
            // Also extract @source suffixes from all known qualified plugin names
            var allQualified = pluginMapService.buildMap(projectSettings).entries().values();
            for (var qualified : allQualified) {
                var atIdx = qualified.indexOf('@');
                if (atIdx >= 0) tags.add(qualified.substring(atIdx + 1));
            }
            var allQualifiedGlobal = pluginMapService.buildMap(globalSettings).entries().values();
            for (var qualified : allQualifiedGlobal) {
                var atIdx = qualified.indexOf('@');
                if (atIdx >= 0) tags.add(qualified.substring(atIdx + 1));
            }

            var out = spec.commandLine().getOut();
            if (tags.isEmpty()) {
                out.println(Quips.EMPTY[0] + " No tags found. Configure marketplace sources in settings.");
            } else {
                tags.forEach(out::println);
            }
            return 0;
        }
    }
}
