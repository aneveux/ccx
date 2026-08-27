package me.neveux.ccx.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import me.neveux.ccx.config.ObjectMapperConfig;
import me.neveux.ccx.model.ClaudePluginInstall;
import me.neveux.ccx.model.ClaudePluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads Claude Code's plugin installation registry
 * ({@code ~/.claude/plugins/installed_plugins.json}) to tell whether an enabled plugin will
 * actually load in a given project.
 *
 * <p>Claude Code keeps enablement and installation in two separate places: a plugin listed in
 * {@code enabledPlugins} that has no installation record for the current project fails to load
 * with {@code plugin-not-installed} or {@code plugin-cache-miss}. This service detects that
 * mismatch so ccx can repair it rather than write a configuration Claude Code rejects.
 *
 * <p>Read-only and advisory: an absent registry means nothing is installed, while an unreadable
 * or malformed one reports nothing missing — a parse failure must not produce a wall of false
 * positives. Installation itself is delegated to the Claude Code CLI, never written here.
 */
@ApplicationScoped
public class ClaudePluginRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ClaudePluginRegistryService.class);

    /** Scopes that apply to every project, and therefore carry no {@code projectPath}. */
    private static final List<String> GLOBAL_SCOPES = List.of("user", "managed");

    /** Marker Claude Code drops in a cache tree it has scheduled for garbage collection. */
    private static final String ORPHAN_MARKER = ".orphaned_at";

    private final ObjectMapper mapper;

    public ClaudePluginRegistryService() {
        this.mapper = ObjectMapperConfig.defaultJson();
    }

    @Inject
    public ClaudePluginRegistryService(@Named("jsonMapper") ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Returns the path of Claude Code's plugin installation registry. Overridable so tests can
     * redirect it away from the real home directory.
     *
     * @return path to {@code installed_plugins.json}, which may not exist
     */
    protected Path registryFile() {
        return Path.of(System.getProperty("user.home"), ".claude", "plugins", "installed_plugins.json");
    }

    /**
     * Tells whether Claude Code has an installation record that makes {@code qualifiedName}
     * loadable from {@code projectRoot}.
     *
     * @param qualifiedName plugin identifier in {@code <plugin>@<marketplace>} form
     * @param projectRoot   root directory of the project the plugin is enabled in
     * @return true when at least one record applies to this project and its install tree is live
     */
    public boolean isInstalledFor(String qualifiedName, Path projectRoot) {
        if (qualifiedName == null || qualifiedName.isBlank()) return false;
        return readRegistry()
            .map(registry -> hasUsableInstall(registry, qualifiedName, canonicalize(projectRoot)))
            .orElse(false);
    }

    /**
     * Returns the enabled plugins that Claude Code cannot load in {@code projectRoot} because no
     * installation record applies to it, in {@code enabledPlugins} declaration order.
     *
     * <p>Keys not mapped to {@code true} are skipped: a plugin that is not enabled needs no
     * install. A registry that exists but cannot be parsed yields an empty list.
     *
     * @param enabledPlugins the {@code enabledPlugins} map from the project's settings; may be null
     * @param projectRoot    root directory of the project
     * @return qualified names needing installation; empty when everything enabled is installed
     */
    public List<String> missingInstalls(Map<String, Boolean> enabledPlugins, Path projectRoot) {
        if (enabledPlugins == null || enabledPlugins.isEmpty()) return List.of();

        var file = registryFile();
        var registry = readRegistry();
        if (registry.isEmpty() && Files.isRegularFile(file)) {
            log.warn("skipping plugin install check, registry unreadable path={}", file);
            return List.of();
        }

        var project = canonicalize(projectRoot);
        var missing = new ArrayList<String>();
        enabledPlugins.forEach((qualifiedName, isEnabled) -> {
            if (!Boolean.TRUE.equals(isEnabled) || qualifiedName == null || qualifiedName.isBlank()) return;
            var installed = registry
                .map(loaded -> hasUsableInstall(loaded, qualifiedName, project))
                .orElse(false);
            if (!installed) missing.add(qualifiedName);
        });
        return List.copyOf(missing);
    }

    private Optional<ClaudePluginRegistry> readRegistry() {
        var file = registryFile();
        if (!Files.isRegularFile(file)) {
            log.debug("Claude Code plugin registry absent path={}", file);
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(mapper.readValue(file.toFile(), ClaudePluginRegistry.class));
        } catch (IOException e) {
            log.warn("could not read Claude Code plugin registry path={}", file, e);
            return Optional.empty();
        }
    }

    private boolean hasUsableInstall(ClaudePluginRegistry registry, String qualifiedName, Path projectRoot) {
        var plugins = registry.plugins();
        if (plugins == null) return false;
        var records = plugins.get(qualifiedName);
        if (records == null || records.isEmpty()) return false;
        return records.stream().anyMatch(install -> applies(install, qualifiedName, projectRoot));
    }

    private boolean applies(ClaudePluginInstall install, String qualifiedName, Path projectRoot) {
        if (install == null) return false;
        if (!coversProject(install, projectRoot)) return false;
        return isInstallTreeLive(install, qualifiedName);
    }

    private boolean coversProject(ClaudePluginInstall install, Path projectRoot) {
        var scope = install.scope();
        if (scope == null || scope.isBlank()) return false;
        if (GLOBAL_SCOPES.contains(scope)) return true;

        var recorded = install.projectPath();
        if (recorded == null || recorded.isBlank() || projectRoot == null) return false;
        return parsePath(recorded)
            .map(path -> canonicalize(path).equals(projectRoot))
            .orElse(false);
    }

    private boolean isInstallTreeLive(ClaudePluginInstall install, String qualifiedName) {
        var location = install.installPath();
        if (location == null || location.isBlank()) {
            log.debug("plugin install record has no install path plugin={}", qualifiedName);
            return false;
        }
        var directory = parsePath(location);
        if (directory.isEmpty()) {
            log.warn("plugin install record has an unusable install path plugin={} path={}", qualifiedName, location);
            return false;
        }
        var tree = directory.get();
        if (!Files.isDirectory(tree)) {
            log.debug("plugin install tree is missing plugin={} path={}", qualifiedName, tree);
            return false;
        }
        if (Files.exists(tree.resolve(ORPHAN_MARKER))) {
            log.debug("plugin install tree is marked orphaned plugin={} path={}", qualifiedName, tree);
            return false;
        }
        return true;
    }

    private static Optional<Path> parsePath(String location) {
        try {
            return Optional.of(Path.of(location));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private static Path canonicalize(Path path) {
        if (path == null) return null;
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
