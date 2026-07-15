package me.neveux.ccx.tui;

import me.neveux.ccx.service.CcxContextService;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.ProfileApplyService;
import me.neveux.ccx.service.ProfileLoadException;
import me.neveux.ccx.service.ProfileNotFoundException;
import me.neveux.ccx.service.ProfileService;
import me.neveux.ccx.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Single-threaded TUI action handlers confined to the render/event thread.
 * Owned by CcxApp; not a CDI bean. The lastError field requires no synchronization.
 */
public final class TuiActions {

    private static final Logger log = LoggerFactory.getLogger(TuiActions.class);

    private String lastError = null;

    /** Returns and clears the last error message, or null if none. */
    public String consumeLastError() {
        var err = lastError;
        lastError = null;
        return err;
    }

    private final SettingsService settingsService;
    private final ClaudeMdService claudeMdService;
    private final TuiDataService tuiDataService;
    private final ProfileApplyService profileApplyService;
    private final CcxContextService ccxContextService;
    private final ProfileService profileService;

    public TuiActions(SettingsService settingsService, ClaudeMdService claudeMdService,
                      TuiDataService tuiDataService, ProfileApplyService profileApplyService,
                      CcxContextService ccxContextService, ProfileService profileService) {
        this.settingsService = settingsService;
        this.claudeMdService = claudeMdService;
        this.tuiDataService = tuiDataService;
        this.profileApplyService = profileApplyService;
        this.ccxContextService = ccxContextService;
        this.profileService = profileService;
    }

    public TuiSnapshot togglePlugin(String pluginName, Path projectDir) {
        return togglePlugin(pluginName, projectDir, Path.of(System.getProperty("user.home")));
    }

    TuiSnapshot togglePlugin(String pluginName, Path projectDir, Path globalRoot) {
        var globalSettings = settingsService.read(globalRoot);
        if (globalSettings.enabledPlugins() != null && globalSettings.enabledPlugins().containsKey(pluginName)) {
            lastError = "Cannot modify global plugin configuration";
            log.debug("plugin toggle blocked — globally enabled name={}", pluginName);
            return tuiDataService.snapshot(projectDir);
        }
        var settings = settingsService.read(projectDir);
        var enabledPlugins = new HashMap<>(settings.enabledPlugins() != null ? settings.enabledPlugins() : Map.<String, Boolean>of());
        if (enabledPlugins.containsKey(pluginName)) {
            enabledPlugins.remove(pluginName);
            log.debug("plugin disabled name={} projectDir={}", pluginName, projectDir);
        } else {
            enabledPlugins.put(pluginName, true);
            log.debug("plugin enabled name={} projectDir={}", pluginName, projectDir);
        }
        settingsService.write(settings.withEnabledPlugins(enabledPlugins), projectDir);
        return tuiDataService.snapshot(projectDir);
    }

    public TuiSnapshot toggleMcp(String serverName, Path projectDir) {
        var settings = settingsService.read(projectDir);
        if (settings.mcpServers() != null && settings.mcpServers().containsKey(serverName)) {
            var mcpServers = new HashMap<>(settings.mcpServers());
            mcpServers.remove(serverName);
            log.debug("mcp server disabled name={} projectDir={}", serverName, projectDir);
            settingsService.write(settings.withMcpServers(mcpServers), projectDir);
        }
        return tuiDataService.snapshot(projectDir);
    }

    public TuiSnapshot addStack(String stackLine, Path projectDir) {
        var claudeMd = projectDir.resolve(".claude/CLAUDE.md");
        var current = claudeMdService.readMarkerBlock(claudeMd);
        if (!current.contains(stackLine)) {
            var updated = new ArrayList<>(current);
            updated.add(stackLine);
            claudeMdService.writeMarkerBlock(claudeMd, updated);
            log.debug("stack added line={} projectDir={}", stackLine, projectDir);
        }
        return tuiDataService.snapshot(projectDir);
    }

    public TuiSnapshot removeStack(String stackLine, Path projectDir) {
        var claudeMd = projectDir.resolve(".claude/CLAUDE.md");
        var current = claudeMdService.readMarkerBlock(claudeMd);
        var updated = new ArrayList<>(current);
        if (updated.remove(stackLine)) {
            claudeMdService.writeMarkerBlock(claudeMd, updated);
            log.debug("stack removed line={} projectDir={}", stackLine, projectDir);
        }
        return tuiDataService.snapshot(projectDir);
    }

    public TuiSnapshot enableProfile(String name, Path projectDir) {
        try {
            // Validate the leaf, then enable its whole extends closure (deps first, leaf last)
            // so a dependent is never recorded enabled without its dependencies.
            profileService.load(name);
            for (var member : profileService.resolveDependencyClosure(name)) {
                try {
                    var profile = profileService.load(member);
                    var snapshot = profileApplyService.apply(profile, projectDir, false);
                    ccxContextService.recordEnabled(member, snapshot, projectDir);
                } catch (ProfileNotFoundException missing) {
                    log.warn("dependency missing on disk dependency={} of={}", member, name);
                }
            }
            log.debug("profile enabled name={} projectDir={}", name, projectDir);
        } catch (ProfileNotFoundException e) {
            lastError = "Profile not found: " + name;
            log.warn("failed to enable profile — not found name={}", name, e);
        } catch (ProfileLoadException e) {
            lastError = "Could not read profile YAML: " + name;
            log.warn("failed to enable profile — YAML load error name={}", name, e);
        } catch (Exception e) {
            lastError = "Failed to enable profile: " + e.getMessage();
            log.warn("failed to enable profile name={}", name, e);
        }
        return tuiDataService.snapshot(projectDir);
    }

    public TuiSnapshot disableProfile(String name, Path projectDir) {
        try {
            var context = ccxContextService.read(projectDir);
            var record = context.enabledProfiles().get(name);
            if (record != null) {
                profileApplyService.remove(record.snapshot(), projectDir, context, name);
                ccxContextService.recordDisabled(name, projectDir);
                log.debug("profile disabled name={} projectDir={}", name, projectDir);
            }
        } catch (Exception e) {
            lastError = "Failed to disable profile: " + e.getMessage();
            log.warn("failed to disable profile name={}", name, e);
        }
        return tuiDataService.snapshot(projectDir);
    }

    public TuiSnapshot reinstallProfile(String name, Path projectDir) {
        try {
            var context = ccxContextService.read(projectDir);
            var record = context.enabledProfiles().get(name);
            if (record != null) {
                profileApplyService.remove(record.snapshot(), projectDir, context, name);
                ccxContextService.recordDisabled(name, projectDir);
            }
            var resolved = profileService.loadAndResolve(name);
            var snapshot = profileApplyService.apply(resolved, projectDir, true);
            ccxContextService.recordEnabled(name, snapshot, projectDir);
            log.debug("profile reinstalled name={} projectDir={}", name, projectDir);
        } catch (ProfileNotFoundException e) {
            lastError = "Profile not found: " + name;
            log.warn("failed to reinstall profile — not found name={}", name, e);
        } catch (ProfileLoadException e) {
            lastError = "Could not read profile YAML: " + name;
            log.warn("failed to reinstall profile — YAML load error name={}", name, e);
        } catch (Exception e) {
            lastError = "Failed to reinstall profile: " + e.getMessage();
            log.warn("failed to reinstall profile name={}", name, e);
        }
        return tuiDataService.snapshot(projectDir);
    }
}
