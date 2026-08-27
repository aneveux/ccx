package me.neveux.ccx.cli;

import me.neveux.ccx.model.ProfileDriftState;
import me.neveux.ccx.service.CcxConfigService;
import me.neveux.ccx.service.CcxContextService;
import me.neveux.ccx.service.ClaudePluginInstallService;
import me.neveux.ccx.service.ClaudePluginRegistryService;
import me.neveux.ccx.util.FileUtils;
import me.neveux.ccx.service.ProfileLifecycleService;
import me.neveux.ccx.service.ProfileNotFoundException;
import me.neveux.ccx.service.ProfileService;
import me.neveux.ccx.service.SettingsService;
import me.neveux.ccx.tui.Quips;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Manages ccx profiles — named, reusable collections of plugins, rules, env vars,
 * permissions, and CLAUDE.md fragments stored under the user-level ccx config
 * directory. Enables/disables profiles per project, tracks applied state in
 * {@code .ccx/context.json}, and detects drift when a profile's definition
 * has changed since it was last applied.
 */
@Command(
    name = "profiles",
    description = "Manage profiles",
    subcommands = {
        ProfilesCommand.ListCommand.class,
        ProfilesCommand.ShowCommand.class,
        ProfilesCommand.EnableCommand.class,
        ProfilesCommand.DisableCommand.class,
        ProfilesCommand.ReinstallCommand.class,
        ProfilesCommand.StatusCommand.class,
        ProfilesCommand.EditCommand.class,
        ProfilesCommand.NewCommand.class,
        CommandLine.HelpCommand.class
    },
    mixinStandardHelpOptions = true
)
@Dependent
@Unremovable
@RegisterForReflection
public class ProfilesCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    /** Prints the names of all available profiles from the global ccx config directory, one per line. */
    @Command(name = "list", description = "List configured profiles")
    @RegisterForReflection
    static class ListCommand implements Runnable {

        @Inject
        ProfileService profileService;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            var profiles = profileService.listAvailableProfiles();
            var out = spec.commandLine().getOut();
            if (profiles.isEmpty()) {
                out.println(Quips.EMPTY[0] + " No profiles found. Create one with: ccx profiles new <name>");
                out.flush();
                return;
            }
            profiles.forEach(out::println);
            out.flush();
        }
    }

    /**
     * Prints the resolved contents of a profile: plugins to enable, rule files,
     * env vars, ignore patterns, permissions, and counts of command/claudemd files.
     * Resolution follows the {@code extends} chain, so inherited values are
     * included. Exits with code 1 if the profile is not found.
     */
    @Command(name = "show", description = "Show details of a named profile")
    @RegisterForReflection
    static class ShowCommand implements Callable<Integer> {

        private static final Logger log = LoggerFactory.getLogger(ShowCommand.class);

        @Inject
        ProfileService profileService;

        @Inject
        CcxConfigService ccxConfigService;

        @Parameters(index = "0", description = "Profile name")
        String name;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            try {
                var resolved = profileService.loadAndResolve(name);

                var out = spec.commandLine().getOut();

                out.println("Plugins:");
                if (resolved.plugins() != null && resolved.plugins().enable() != null) {
                    resolved.plugins().enable().forEach(p -> out.println("  " + p));
                }

                if (resolved.env() != null && !resolved.env().isEmpty()) {
                    out.println("Env:");
                    resolved.env().forEach((k, v) -> out.println("  " + k + "=" + v));
                }

                if (resolved.ignore() != null && !resolved.ignore().isEmpty()) {
                    out.println("Ignore:");
                    resolved.ignore().forEach(p -> out.println("  " + p));
                }

                if (resolved.permissions() != null && resolved.permissions().allow() != null && !resolved.permissions().allow().isEmpty()) {
                    out.println("Permissions (allow):");
                    resolved.permissions().allow().forEach(p -> out.println("  " + p));
                }

                if (ccxConfigService != null) {
                    var rulesDir = ccxConfigService.profileDir(name).resolve("rules");
                    if (Files.isDirectory(rulesDir)) {
                        try (var stream = Files.list(rulesDir)) {
                            var ruleNames = stream
                                .filter(p -> p.getFileName().toString().endsWith(".md"))
                                .map(p -> p.getFileName().toString().replaceAll("\\.md$", ""))
                                .sorted()
                                .toList();
                            if (!ruleNames.isEmpty()) {
                                out.println("Rules:");
                                ruleNames.forEach(r -> out.println("  " + r));
                            }
                        } catch (IOException e) {
                            log.debug("failed to list rules dir for profile name={} dir={}", name, rulesDir, e);
                        }
                    }
                    var cmdDir = ccxConfigService.profileDir(name).resolve("commands");
                    if (Files.isDirectory(cmdDir)) {
                        try (var stream = Files.list(cmdDir)) {
                            var count = stream.filter(p -> p.getFileName().toString().endsWith(".md")).count();
                            if (count > 0) {
                                out.println("Commands: " + count + " file(s)");
                            }
                        } catch (IOException e) {
                            log.debug("failed to list commands dir for profile name={} dir={}", name, cmdDir, e);
                        }
                    }
                    var fragDir = ccxConfigService.profileClaudeMdDir(name);
                    if (Files.isDirectory(fragDir)) {
                        try (var stream = Files.list(fragDir)) {
                            var count = stream.filter(p -> p.getFileName().toString().endsWith(".md")).count();
                            if (count > 0) {
                                out.println("Claudemd: " + count + " file(s)");
                            }
                        } catch (IOException e) {
                            log.debug("failed to list claudemd dir for profile name={} dir={}", name, fragDir, e);
                        }
                    }
                }

                return 0;
            } catch (ProfileNotFoundException e) {
                spec.commandLine().getErr().println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    /**
     * Applies a profile (and its full {@code extends} dependency closure) to the
     * project directory, then records the applied snapshot in
     * {@code context.json}. Dependencies are enabled in topological order so
     * each is recorded before the profile that depends on it. A closure member
     * already recorded enabled is skipped, so the command is idempotent and exits
     * 0 even when there is nothing left to apply; {@code reinstall} re-applies.
     */
    @Command(name = "enable", description = "Enable a profile in this project")
    @RegisterForReflection
    static class EnableCommand implements Callable<Integer> {

        @Inject
        ProfileLifecycleService profileLifecycleService;

        @Inject
        SettingsService settingsService;

        @Inject
        ClaudePluginRegistryService claudePluginRegistryService;

        @Inject
        ClaudePluginInstallService claudePluginInstallService;

        @Parameters(index = "0", description = "Profile name")
        String name;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var dir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(dir, spec)) return 1;
            try {
                var result = profileLifecycleService.enable(name, dir);
                for (var missing : result.missingDependencies()) {
                    spec.commandLine().getErr().println(
                        "Warning: dependency '" + missing + "' of '" + name + "' has no profile on disk — '" + name + "' will be marked broken.");
                }
                var out = spec.commandLine().getOut();
                var skippedDependencies = result.alreadyEnabledDependencies(name);
                if (!skippedDependencies.isEmpty()) {
                    out.println("Already enabled: " + String.join(", ", skippedDependencies));
                }
                if (result.wasAlreadyEnabled(name)) {
                    out.println("Profile '" + name + "' is already enabled. Run `ccx profiles reinstall "
                        + name + "` to re-apply it.");
                } else {
                    out.println("Enabled profile: " + name);
                }
                out.flush();
                // The profile's plugins are now enabled in settings.json; Claude Code still needs
                // them installed. A failure here is warned about, not fatal — the enablement stands
                // and `ccx plugins sync` repairs it.
                installProfilePlugins(dir);
                return 0;
            } catch (ProfileNotFoundException e) {
                spec.commandLine().getErr().println("Profile '" + name + "' not found. Run `ccx profiles list` to see available profiles, or `ccx profiles new " + name + "` to create one.");
                return 1;
            } catch (IOException e) {
                spec.commandLine().getErr().println("Failed to record profile state (context.json may be corrupted): " + e.getMessage());
                return 1;
            }
        }

        private void installProfilePlugins(Path dir) {
            PluginsCommand.installMissingPlugins(claudePluginRegistryService, claudePluginInstallService,
                settingsService.read(dir), dir, spec, false);
        }
    }

    /**
     * Reverts the plugin/rule/env changes introduced by the named profile and
     * removes it from {@code context.json}. Exits with code 1 if the profile is
     * not currently enabled.
     */
    @Command(name = "disable", description = "Disable a profile in this project")
    @RegisterForReflection
    static class DisableCommand implements Callable<Integer> {

        @Inject
        ProfileLifecycleService profileLifecycleService;

        @Parameters(index = "0", description = "Profile name")
        String name;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var dir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(dir, spec)) return 1;
            try {
                if (!profileLifecycleService.disable(name, dir)) {
                    spec.commandLine().getErr().println("Profile '" + name + "' is not enabled");
                    return 1;
                }
            } catch (IOException e) {
                spec.commandLine().getErr().println("Failed to record profile state (context.json may be corrupted): " + e.getMessage());
                return 1;
            }
            var out = spec.commandLine().getOut();
            out.println("Disabled profile: " + name);
            out.flush();
            return 0;
        }
    }

    /**
     * Removes and re-applies a profile in a single step. Useful when the profile
     * definition has drifted and {@code ccx profiles status} reports the profile as
     * DRIFTED. If the profile is not currently enabled it is applied from scratch.
     */
    @Command(name = "reinstall", description = "Reinstall a profile in this project")
    @RegisterForReflection
    static class ReinstallCommand implements Callable<Integer> {

        @Inject
        ProfileLifecycleService profileLifecycleService;

        @Inject
        SettingsService settingsService;

        @Inject
        ClaudePluginRegistryService claudePluginRegistryService;

        @Inject
        ClaudePluginInstallService claudePluginInstallService;

        @Parameters(index = "0", description = "Profile name")
        String name;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var dir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(dir, spec)) return 1;
            try {
                profileLifecycleService.reinstall(name, dir);
                var out = spec.commandLine().getOut();
                out.println("Reinstalled profile: " + name);
                out.flush();
                PluginsCommand.installMissingPlugins(claudePluginRegistryService, claudePluginInstallService,
                    settingsService.read(dir), dir, spec, false);
                return 0;
            } catch (ProfileNotFoundException e) {
                spec.commandLine().getErr().println("Profile '" + name + "' not found. Run `ccx profiles list` to see available profiles, or `ccx profiles new " + name + "` to create one.");
                return 1;
            } catch (IOException e) {
                spec.commandLine().getErr().println("Failed to record profile state (context.json may be corrupted): " + e.getMessage());
                return 1;
            }
        }
    }

    /**
     * Opens the {@code profile.yaml} for the named profile in the editor specified
     * by {@code $EDITOR}. Fails if the profile does not exist or {@code $EDITOR}
     * is unset.
     */
    @Command(name = "edit", description = "Edit a profile")
    @RegisterForReflection
    static class EditCommand implements Callable<Integer> {

        @Inject
        CcxConfigService ccxConfigService;

        @Parameters(index = "0", description = "Profile name")
        String name;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        Supplier<String> editorSupplier = () -> System.getenv("EDITOR");

        @Override
        public Integer call() {
            if (!name.matches(CommandUtil.VALID_NAME_PATTERN)) {
                spec.commandLine().getErr().println("Error: invalid profile name: " + name);
                return 1;
            }
            var profileYaml = ccxConfigService.profileDir(name).resolve("profile.yaml");
            if (!Files.exists(profileYaml)) {
                spec.commandLine().getErr().println("Profile '" + name + "' not found. Run `ccx profiles new " + name + "` to create one.");
                return 1;
            }
            var editor = editorSupplier.get();
            if (editor == null || editor.isBlank()) {
                spec.commandLine().getErr().println("Error: $EDITOR is not set. Set it to edit profiles.");
                return 1;
            }
            try {
                var tokens = editor.strip().split("\\s+");
                var command = new ArrayList<String>(Arrays.asList(tokens));
                command.add(profileYaml.toString());
                var process = new ProcessBuilder(command)
                        .inheritIO()
                        .start();
                try {
                    process.waitFor();
                } finally {
                    process.destroy();
                }
                spec.commandLine().getOut().println("Edited: " + profileYaml);
                return 0;
            } catch (IOException e) {
                spec.commandLine().getErr().println("Error editing profile: " + e.getMessage());
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                spec.commandLine().getErr().println("Editor interrupted.");
                return 1;
            }
        }
    }

    /**
     * Creates the profile directory structure under the global ccx config directory,
     * including {@code claudemd/}, {@code rules/}, {@code commands/} subdirectories
     * and a minimal {@code profile.yaml} template. Fails if a profile with the same
     * name already exists, or if the name does not match {@code [a-z0-9-]+}.
     */
    @Command(name = "new", description = "Scaffold a new profile directory")
    @RegisterForReflection
    static class NewCommand implements Callable<Integer> {

        @Inject
        CcxConfigService ccxConfigService;

        @Parameters(index = "0", description = "Profile name (lowercase letters, digits, hyphens)")
        String name;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            if (!name.matches(CommandUtil.VALID_NAME_PATTERN)) {
                spec.commandLine().getErr().println("Error: invalid profile name '" + name + "': only lowercase letters, digits, and hyphens allowed");
                return 1;
            }
            var profileDir = ccxConfigService.profileDir(name);
            var profileYaml = profileDir.resolve("profile.yaml");
            if (Files.exists(profileDir) && Files.exists(profileYaml)) {
                spec.commandLine().getErr().println("Error: profile '" + name + "' already exists at " + profileDir);
                return 1;
            }
            try {
                Files.createDirectories(profileDir.resolve("claudemd"));
                Files.createDirectories(profileDir.resolve("rules"));
                Files.createDirectories(profileDir.resolve("commands"));
                var template = "metadata:\n  name: " + name + "\n  description: \"\"\n\nextends: []\n\nplugins:\n  enable: []\n\n# env:\n#   ENABLE_LSP_TOOL: \"1\"\n\n# permissions:\n#   allow:\n#     - \"Bash(mvn *)\"\n\n# Place .md files in claudemd/ directory for CLAUDE.md injection\n\n# ignore:\n#   - \"target/\"\n";
                FileUtils.atomicWrite(profileYaml, template);
                spec.commandLine().getOut().println("Profile '" + name + "' scaffolded at " + profileDir + ". Edit profile.yaml to configure plugins and extends.");
                return 0;
            } catch (IOException e) {
                spec.commandLine().getErr().println("Error creating profile '" + name + "': " + e.getMessage());
                return 1;
            }
        }
    }

    /**
     * Reports the drift state of every available profile against the current
     * project. Each line is formatted as {@code [STATE] profileName} where STATE
     * is one of DISABLED, ENABLED, DRIFTED, or BROKEN.
     */
    @Command(name = "status", description = "Show the status of all profiles in this project")
    @RegisterForReflection
    static class StatusCommand implements Callable<Integer> {

        @Inject
        ProfileService profileService;

        @Inject
        CcxContextService ccxContextService;

        @Inject
        SettingsService settingsService;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var dir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(dir, spec)) return 1;
            var context = ccxContextService.read(dir);
            var liveSettings = settingsService.read(dir);
            var claudeDir = dir.resolve(".claude");
            var out = spec.commandLine().getOut();

            var profileNames = profileService.listAvailableProfiles();
            profileNames.forEach(profileName -> {
                var record = context.enabledProfiles().get(profileName);
                ProfileDriftState state;
                if (record == null) {
                    state = ProfileDriftState.DISABLED;
                } else if (!profileService.missingDependencies(profileName, context.enabledProfiles().keySet()).isEmpty()) {
                    state = ProfileDriftState.BROKEN;
                } else {
                    state = ccxContextService.detectDrift(profileName, record.snapshot(), liveSettings, claudeDir);
                }
                out.println("[" + state.name() + "] " + profileName);
            });
            out.flush();
            return 0;
        }
    }
}
