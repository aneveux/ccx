package me.neveux.ccx.cli;

import me.neveux.ccx.model.Settings;
import me.neveux.ccx.service.CcxConfigService;
import me.neveux.ccx.service.CcxContextService;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.ClaudePluginRegistryService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.ProfileService;
import me.neveux.ccx.service.SettingsService;
import me.neveux.ccx.tui.Quips;
import me.neveux.ccx.util.ProcessRunner;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Validates a project's ccx configuration and reports issues categorised by
 * severity: [OK], [INFO], [WARNING], [ERROR]. Checks include settings.json
 * parse validity, CLAUDE.md fragment paths, enabled plugin marketplace
 * membership, MCP server declarations, required tools on PATH, global settings
 * parse validity, ccx config directory presence, and profile dependency
 * completeness.
 *
 * <p>Exits with code 1 if any ERROR is found, or if {@code git} is missing from PATH; every other
 * WARNING and INFO is advisory and leaves the exit code at 0. In particular a plugin that resolves
 * against no configured marketplace stays a non-failing WARNING (see ADR-015).
 */
@Command(name = "doctor", description = "Validate project configuration")
@Dependent
@Unremovable
public class DoctorCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(DoctorCommand.class);

    /** Long enough for a cold PATH lookup, short enough that a wedged one does not hang doctor. */
    private static final int PATH_PROBE_TIMEOUT_SECONDS = 5;

    private final SettingsService settingsService;
    private final PluginMapService pluginMapService;
    private final ClaudeMdService claudeMdService;
    private final CcxConfigService ccxConfigService;
    private final ProfileService profileService;
    private final CcxContextService ccxContextService;
    private final ClaudePluginRegistryService claudePluginRegistryService;

    DoctorCommand() {
        this(new SettingsService(), new PluginMapService(), new ClaudeMdService(), new CcxConfigService(),
             new ProfileService(), new CcxContextService(), new ClaudePluginRegistryService());
    }

    @Inject
    public DoctorCommand(SettingsService settingsService, PluginMapService pluginMapService,
                         ClaudeMdService claudeMdService, CcxConfigService ccxConfigService,
                         ProfileService profileService, CcxContextService ccxContextService,
                         ClaudePluginRegistryService claudePluginRegistryService) {
        this.settingsService = settingsService;
        this.pluginMapService = pluginMapService;
        this.claudeMdService = claudeMdService;
        this.ccxConfigService = ccxConfigService;
        this.profileService = profileService;
        this.ccxContextService = ccxContextService;
        this.claudePluginRegistryService = claudePluginRegistryService;
    }

    @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
    Path projectDir;

    @Option(names = {"--global-dir"}, description = "Global config dir (defaults to user.home)")
    Path globalDir;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    private record SettingsCheckResult(boolean hasError, Settings settings, boolean readable) {}

    private record GlobalSettingsResult(boolean present, Settings settings, String parseError) {}

    @Override
    public Integer call() {
        var dir = CommandUtil.resolveProjectDir(projectDir);
        if (!CommandUtil.checkPathSecurity(dir, spec)) return 1;
        if (globalDir != null && !CommandUtil.checkPathSecurity(globalDir, spec, "--global-dir")) return 1;
        var out = spec.commandLine().getOut();
        var hasErrors = false;

        var globalRoot = globalDir != null ? globalDir : Path.of(System.getProperty("user.home"));
        var globalResult = readGlobalSettings(globalRoot);

        var settingsResult = checkSettingsFile(dir, out);
        hasErrors |= settingsResult.hasError();
        hasErrors |= checkClaudeMdRefs(dir, out);
        if (settingsResult.readable()) {
            hasErrors |= checkPlugins(settingsResult.settings(), globalResult.settings(), dir, out);
            checkMcpServers(settingsResult.settings(), out);
            checkSkillOverrides(settingsResult.settings(), out);
            checkRulesDir(dir, out);
        }
        hasErrors |= checkDependencies(out);
        hasErrors |= reportGlobalSettings(globalResult, out);
        hasErrors |= checkCcxConfig(out);
        hasErrors |= checkProfileDependencies(dir, out);

        if (hasErrors) {
            emit(out, "[WARNING] doctor found issues above");
        } else {
            var quip = Quips.pick(Quips.ALLGOOD, dir.toString().hashCode());
            emit(out, "[OK] all checks passed  " + quip);
        }

        log.info("doctor completed dir={} hasErrors={}", dir, hasErrors);
        return hasErrors ? 1 : 0;
    }

    private SettingsCheckResult checkSettingsFile(Path dir, PrintWriter out) {
        var settingsFile = dir.resolve(".claude/settings.json");
        if (!Files.exists(settingsFile)) {
            emit(out, "[OK] settings.json absent (no configuration yet)");
            return new SettingsCheckResult(false, new Settings(), true);
        }
        try {
            var settings = settingsService.readOrThrow(dir);
            emit(out, "[OK] settings.json is valid JSON");
            return new SettingsCheckResult(false, settings, true);
        } catch (IOException e) {
            emit(out, "[ERROR] settings.json is malformed: " + e.getMessage());
            log.debug("settings.json malformed path={}", settingsFile, e);
            return new SettingsCheckResult(true, new Settings(), false);
        }
    }

    private boolean checkClaudeMdRefs(Path dir, PrintWriter out) {
        var claudeMdFile = dir.resolve(".claude/CLAUDE.md");
        var stacks = claudeMdService.readMarkerBlock(claudeMdFile);
        var hasError = false;
        for (var stackLine : stacks) {
            if (stackLine.startsWith("@")) {
                var rawPath = stackLine.substring(1);
                var resolvedPath = rawPath.startsWith("~/")
                    ? Path.of(System.getProperty("user.home"), rawPath.substring(2))
                    : Path.of(rawPath);
                if (Files.exists(resolvedPath)) {
                    emit(out, "[OK] stack ref exists: " + stackLine);
                } else {
                    emit(out, "[ERROR] stack ref missing: " + stackLine);
                    hasError = true;
                    log.debug("stack ref missing path={}", resolvedPath);
                }
            }
        }
        return hasError;
    }

    /**
     * Reports each enabled plugin's marketplace membership, then whether Claude Code can actually
     * load it in this project. A plugin enabled without an installation record is an ERROR, not a
     * warning: Claude Code refuses to load it and says so on every launch.
     *
     * @return true when at least one enabled plugin is not installed for this project
     */
    private boolean checkPlugins(Settings settings, Settings globalSettings, Path dir, PrintWriter out) {
        if (settings.enabledPlugins() == null || settings.enabledPlugins().isEmpty()) {
            emit(out, "[INFO] 0 plugin(s) enabled");
            return false;
        }
        var marketplaceMap = (hasMarketplaces(settings) || hasMarketplaces(globalSettings))
            ? pluginMapService.buildMap(settings, globalSettings).entries()
            : null;
        settings.enabledPlugins().forEach((pluginKey, enabled) -> {
            if (!enabled) {
                emit(out, "[INFO] plugin disabled: " + pluginKey);
            } else if (marketplaceMap == null) {
                emit(out, "[INFO] plugin enabled: " + pluginKey + " (unverified — no marketplace configured)");
            } else if (marketplaceMap.containsValue(pluginKey)) {
                emit(out, "[OK] plugin enabled: " + pluginKey + " (verified)");
            } else {
                emit(out, "[WARNING] plugin enabled: " + pluginKey + " (not found in marketplace)");
            }
        });

        var missing = claudePluginRegistryService.missingInstalls(settings.enabledPlugins(), dir);
        missing.forEach(pluginKey -> emit(out,
            "[ERROR] plugin enabled but not installed for this project: " + pluginKey
                + " — run `ccx plugins sync`"));
        return !missing.isEmpty();
    }

    private static boolean hasMarketplaces(Settings settings) {
        return settings.extraKnownMarketplaces() != null && !settings.extraKnownMarketplaces().isEmpty();
    }

    private void checkMcpServers(Settings settings, PrintWriter out) {
        int mcpCount = settings.mcpServers() == null ? 0 : settings.mcpServers().size();
        if (mcpCount == 0) {
            emit(out, "[INFO] no MCP servers configured");
        } else {
            var names = settings.mcpServers().entrySet().stream()
                .map(e -> e.getKey() + " (" + e.getValue().type() + ")")
                .collect(Collectors.joining(", "));
            emit(out, "[INFO] " + mcpCount + " MCP server(s): " + names);
        }
    }

    private void checkSkillOverrides(Settings settings, PrintWriter out) {
        int overrideCount = settings.skillOverrides() == null ? 0 : settings.skillOverrides().size();
        emit(out, "[INFO] " + overrideCount + " skill override(s)");
    }

    private void checkRulesDir(Path dir, PrintWriter out) {
        var rulesDir = dir.resolve(".claude/rules");
        if (Files.exists(rulesDir) && Files.isDirectory(rulesDir)) {
            long rulesCount;
            try (var stream = Files.list(rulesDir)) {
                rulesCount = stream.count();
            } catch (IOException e) {
                log.warn("failed to list rules dir path={}", rulesDir, e);
                rulesCount = 0;
            }
            emit(out, "[INFO] rules: .claude/rules/ — " + rulesCount + " file(s)");
        } else {
            emit(out, "[INFO] no rules directory (.claude/rules/ absent)");
        }
    }

    /** Answers whether {@code tool} is on the PATH, or throws when the probe itself could not run. */
    @FunctionalInterface
    interface PathProbe {
        boolean isOnPath(String tool) throws IOException;
    }

    private boolean checkDependencies(PrintWriter out) {
        return checkDependencies(out, DoctorCommand::isOnPath);
    }

    /**
     * Package-private overload taking the probe, so a test can drive the branch where the probe
     * never answers — a real wedged PATH lookup is not reproducible on demand.
     */
    boolean checkDependencies(PrintWriter out, PathProbe probe) {
        var hasError = false;
        for (var tool : List.of("git", "jq", "yq")) {
            try {
                if (probe.isOnPath(tool)) {
                    emit(out, "[OK] " + tool + " found on PATH");
                } else {
                    var level = "git".equals(tool) ? "[WARNING]" : "[INFO]";
                    emit(out, level + " " + tool + " not found on PATH");
                    if ("git".equals(tool)) hasError = true;
                }
            } catch (IOException e) {
                log.warn("PATH probe failed tool={}", tool, e);
                emit(out, "[INFO] could not check " + tool + " on PATH");
            }
        }
        return hasError;
    }

    /**
     * Runs {@code which <tool>} through {@link ProcessRunner} so the wait is bounded. A plain
     * {@code Process.waitFor()} has no timeout, and a PATH entry that stopped answering — a dead
     * automount, an unresponsive network mount — left {@code ccx doctor} hanging with no output.
     *
     * @throws IOException if the probe cannot be started, times out, or is interrupted
     */
    private static boolean isOnPath(String tool) throws IOException {
        return ProcessRunner.run(List.of("which", tool), null, null, PATH_PROBE_TIMEOUT_SECONDS).isSuccess();
    }

    /**
     * Reads the user-level {@code settings.json} once, so both the global-settings check and the
     * plugin marketplace verification can share the result without hitting the file twice.
     */
    private GlobalSettingsResult readGlobalSettings(Path globalRoot) {
        var globalSettingsFile = globalRoot.resolve(".claude/settings.json");
        if (!Files.exists(globalSettingsFile)) {
            return new GlobalSettingsResult(false, new Settings(), null);
        }
        try {
            return new GlobalSettingsResult(true, settingsService.readOrThrow(globalRoot), null);
        } catch (IOException e) {
            log.debug("global settings.json malformed path={}", globalSettingsFile, e);
            return new GlobalSettingsResult(true, new Settings(), e.getMessage());
        }
    }

    private boolean reportGlobalSettings(GlobalSettingsResult globalResult, PrintWriter out) {
        if (!globalResult.present()) {
            return false;
        }
        if (globalResult.parseError() != null) {
            emit(out, "[ERROR] global settings.json is malformed: " + globalResult.parseError());
            return true;
        }
        var globalSettings = globalResult.settings();
        int globalPluginCount = globalSettings.enabledPlugins() == null ? 0 : globalSettings.enabledPlugins().size();
        emit(out, "[INFO] global plugins: " + globalPluginCount);
        return false;
    }

    private boolean checkCcxConfig(PrintWriter out) {
        var ccxConfigDir = ccxConfigService.configDir();
        if (!Files.exists(ccxConfigDir)) {
            emit(out, "[INFO] ccx config directory not found: " + ccxConfigDir + " — run `ccx profiles new <name>` to create one");
            return false;
        }
        var profilesDir = ccxConfigService.profilesDir();
        if (Files.exists(profilesDir)) {
            long profileCount;
            try (var stream = Files.list(profilesDir)) {
                profileCount = stream.filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve("profile.yaml")))
                    .count();
            } catch (IOException e) {
                log.warn("failed to list profiles dir path={}", profilesDir, e);
                profileCount = 0;
            }
            emit(out, "[OK] ccx config: " + ccxConfigDir + " (" + profileCount + " profile(s))");
        } else {
            emit(out, "[OK] ccx config: " + ccxConfigDir + " (no profiles directory yet)");
        }
        return false;
    }

    private boolean checkProfileDependencies(Path dir, PrintWriter out) {
        var ccxContext = ccxContextService.read(dir);
        var enabledNames = ccxContext.enabledProfiles().keySet();
        var hasError = false;
        for (var enabledName : enabledNames) {
            List<String> missing;
            try {
                missing = profileService.missingDependencies(enabledName, enabledNames);
            } catch (RuntimeException e) {
                log.debug("could not resolve dependencies for profile name={}", enabledName, e);
                continue;
            }
            if (!missing.isEmpty()) {
                emit(out, "[WARNING] profile " + enabledName + " has missing dependency: " + String.join(", ", missing));
                hasError = true;
            }
        }
        return hasError;
    }

    /**
     * Print a check line. Piped/CI output keeps the bracketed tag verbatim (parseable,
     * test-stable); an interactive TTY swaps the tag for a Catppuccin-coloured glyph.
     */
    private static void emit(PrintWriter out, String line) {
        if (!CliFormat.isTty()) {
            out.println(line);
            return;
        }
        if (line.startsWith("[OK] ")) {
            out.println(CliFormat.green(CliFormat.OK) + " " + line.substring(5));
        } else if (line.startsWith("[ERROR] ")) {
            out.println(CliFormat.red(CliFormat.ERR) + " " + line.substring(8));
        } else if (line.startsWith("[WARNING] ")) {
            out.println(CliFormat.yellow(CliFormat.WARN) + " " + line.substring(10));
        } else if (line.startsWith("[INFO] ")) {
            out.println(CliFormat.dim(CliFormat.INFO) + " " + line.substring(7));
        } else {
            out.println(line);
        }
    }
}
