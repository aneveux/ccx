package me.neveux.ccx.tui;

import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.ConfigReadException;
import me.neveux.ccx.service.ConfigWriteException;
import me.neveux.ccx.service.ProfileLifecycleService;
import me.neveux.ccx.service.ProfileLoadException;
import me.neveux.ccx.service.ProfileNotFoundException;
import me.neveux.ccx.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private final ProfileLifecycleService profileLifecycleService;

    public TuiActions(SettingsService settingsService, ClaudeMdService claudeMdService,
                      TuiDataService tuiDataService, ProfileLifecycleService profileLifecycleService) {
        this.settingsService = settingsService;
        this.claudeMdService = claudeMdService;
        this.tuiDataService = tuiDataService;
        this.profileLifecycleService = profileLifecycleService;
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
        try {
            var settings = settingsService.readForUpdate(projectDir);
            var enabledPlugins = new LinkedHashMap<>(settings.enabledPlugins() != null ? settings.enabledPlugins() : Map.<String, Boolean>of());
            if (enabledPlugins.containsKey(pluginName)) {
                enabledPlugins.remove(pluginName);
                log.debug("plugin disabled name={} projectDir={}", pluginName, projectDir);
            } else {
                enabledPlugins.put(pluginName, true);
                log.debug("plugin enabled name={} projectDir={}", pluginName, projectDir);
            }
            settingsService.write(settings.withEnabledPlugins(enabledPlugins), projectDir);
        } catch (ConfigReadException | ConfigWriteException e) {
            lastError = e.getMessage();
            log.warn("plugin toggle aborted name={} projectDir={}", pluginName, projectDir, e);
        }
        return tuiDataService.snapshot(projectDir);
    }

    public TuiSnapshot toggleMcp(String serverName, Path projectDir) {
        try {
            var settings = settingsService.readForUpdate(projectDir);
            if (settings.mcpServers() != null && settings.mcpServers().containsKey(serverName)) {
                var mcpServers = new LinkedHashMap<>(settings.mcpServers());
                mcpServers.remove(serverName);
                log.debug("mcp server disabled name={} projectDir={}", serverName, projectDir);
                settingsService.write(settings.withMcpServers(mcpServers), projectDir);
            }
        } catch (ConfigReadException | ConfigWriteException e) {
            lastError = e.getMessage();
            log.warn("mcp toggle aborted name={} projectDir={}", serverName, projectDir, e);
        }
        return tuiDataService.snapshot(projectDir);
    }

    public TuiSnapshot addStack(String stackLine, Path projectDir) {
        var claudeMd = projectDir.resolve(".claude/CLAUDE.md");
        try {
            var current = claudeMdService.readMarkerBlock(claudeMd);
            if (!current.contains(stackLine)) {
                var updated = new ArrayList<>(current);
                updated.add(stackLine);
                claudeMdService.writeMarkerBlock(claudeMd, updated);
                log.debug("stack added line={} projectDir={}", stackLine, projectDir);
            }
        } catch (ConfigReadException | ConfigWriteException e) {
            lastError = e.getMessage();
            log.warn("stack add aborted line={} projectDir={}", stackLine, projectDir, e);
        }
        return tuiDataService.snapshot(projectDir);
    }

    public TuiSnapshot removeStack(String stackLine, Path projectDir) {
        var claudeMd = projectDir.resolve(".claude/CLAUDE.md");
        try {
            var current = claudeMdService.readMarkerBlock(claudeMd);
            var updated = new ArrayList<>(current);
            if (updated.remove(stackLine)) {
                claudeMdService.writeMarkerBlock(claudeMd, updated);
                log.debug("stack removed line={} projectDir={}", stackLine, projectDir);
            }
        } catch (ConfigReadException | ConfigWriteException e) {
            lastError = e.getMessage();
            log.warn("stack remove aborted line={} projectDir={}", stackLine, projectDir, e);
        }
        return tuiDataService.snapshot(projectDir);
    }

    public TuiSnapshot enableProfile(String name, Path projectDir) {
        try {
            profileLifecycleService.enable(name, projectDir);
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
            profileLifecycleService.disable(name, projectDir);
        } catch (Exception e) {
            lastError = "Failed to disable profile: " + e.getMessage();
            log.warn("failed to disable profile name={}", name, e);
        }
        return tuiDataService.snapshot(projectDir);
    }

    public TuiSnapshot reinstallProfile(String name, Path projectDir) {
        try {
            profileLifecycleService.reinstall(name, projectDir);
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
