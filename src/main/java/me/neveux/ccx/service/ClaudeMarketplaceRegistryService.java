package me.neveux.ccx.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import me.neveux.ccx.config.ObjectMapperConfig;
import me.neveux.ccx.model.ClaudeMarketplaceRegistryEntry;
import me.neveux.ccx.model.MarketplaceSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads Claude Code's own marketplace registry
 * ({@code ~/.claude/plugins/known_marketplaces.json}) so ccx resolves marketplaces from the
 * directories Claude Code has already populated instead of re-fetching them.
 *
 * <p>This matters for two cases that {@code extraKnownMarketplaces} alone cannot cover:
 * <ul>
 *   <li>A {@code github} marketplace on a private repository. Claude Code clones over SSH
 *       with the user's credentials; {@link MarketplaceCloneService} clones over https with
 *       {@code GIT_TERMINAL_PROMPT=0} and therefore cannot authenticate.</li>
 *   <li>A marketplace registered directly with Claude Code and never mirrored into any
 *       {@code settings.json}. Claude Code loads its plugins, so ccx must see it too.</li>
 * </ul>
 *
 * <p>The registry is advisory: an absent, unreadable, or malformed file leaves the caller's
 * own marketplace sources untouched rather than failing the command.
 */
@ApplicationScoped
public class ClaudeMarketplaceRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeMarketplaceRegistryService.class);

    private static final TypeReference<Map<String, ClaudeMarketplaceRegistryEntry>> REGISTRY_TYPE =
        new TypeReference<>() {};

    private final ObjectMapper mapper;

    public ClaudeMarketplaceRegistryService() {
        this.mapper = ObjectMapperConfig.defaultJson();
    }

    @Inject
    public ClaudeMarketplaceRegistryService(@Named("jsonMapper") ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Returns the path of Claude Code's marketplace registry file. Overridable so tests can
     * redirect it away from the real home directory.
     *
     * @return path to {@code known_marketplaces.json}, which may not exist
     */
    protected Path registryFile() {
        return Path.of(System.getProperty("user.home"), ".claude", "plugins", "known_marketplaces.json");
    }

    /**
     * Returns the marketplaces Claude Code has installed locally, mapped from marketplace key
     * to the directory holding their contents, in registry order.
     *
     * <p>Entries with a blank key, a missing install location, an unparseable path, or an
     * install location that is not an existing directory are dropped.
     *
     * @return key to directory mappings; empty when the registry is absent or unreadable
     */
    public Map<String, Path> installedMarketplaces() {
        var file = registryFile();
        if (!Files.isRegularFile(file)) {
            log.debug("Claude Code marketplace registry absent path={}", file);
            return Map.of();
        }

        Map<String, ClaudeMarketplaceRegistryEntry> entries;
        try {
            entries = mapper.readValue(file.toFile(), REGISTRY_TYPE);
        } catch (IOException e) {
            log.warn("could not read Claude Code marketplace registry path={}", file, e);
            return Map.of();
        }
        if (entries == null) {
            log.warn("Claude Code marketplace registry is empty or null path={}", file);
            return Map.of();
        }

        var installed = new LinkedHashMap<String, Path>();
        entries.forEach((key, entry) -> {
            if (key == null || key.isBlank() || entry == null) return;
            installedDirectory(key, entry).ifPresent(directory -> installed.put(key, directory));
        });
        return installed;
    }

    /**
     * Rewrites {@code sources} so that every marketplace Claude Code has installed locally
     * resolves to that directory, and appends the marketplaces only Claude Code knows about.
     *
     * <p>A Claude Code install location wins over the declared source descriptor because it is
     * what Claude Code actually loads, and because it is already local — no clone, no
     * authentication, no staleness window.
     *
     * @param sources marketplace sources declared in {@code settings.json}; may be null
     * @return the augmented source list, in declaration order followed by registry-only entries
     */
    public List<MarketplaceSource> withInstalledLocations(List<MarketplaceSource> sources) {
        var declared = sources == null ? List.<MarketplaceSource>of() : sources;
        var installed = installedMarketplaces();
        if (installed.isEmpty()) {
            return declared;
        }

        var declaredKeys = new LinkedHashSet<String>();
        var augmented = new ArrayList<MarketplaceSource>(declared.size() + installed.size());
        for (var source : declared) {
            if (source == null) continue;
            declaredKeys.add(source.key());
            var directory = installed.get(source.key());
            if (directory == null) {
                augmented.add(source);
                continue;
            }
            log.debug("marketplace resolved from Claude Code registry key={} path={}", source.key(), directory);
            augmented.add(directorySource(source.key(), directory));
        }
        installed.forEach((key, directory) -> {
            if (declaredKeys.contains(key)) return;
            log.debug("marketplace known only to Claude Code key={} path={}", key, directory);
            augmented.add(directorySource(key, directory));
        });
        return augmented;
    }

    private Optional<Path> installedDirectory(String key, ClaudeMarketplaceRegistryEntry entry) {
        var location = entry.installLocation();
        if (location == null || location.isBlank()) {
            log.debug("marketplace registry entry has no install location key={}", key);
            return Optional.empty();
        }
        Path directory;
        try {
            directory = Path.of(location);
        } catch (InvalidPathException e) {
            log.warn("marketplace registry entry has an unusable install location key={} location={}", key, location, e);
            return Optional.empty();
        }
        if (!Files.isDirectory(directory)) {
            log.debug("marketplace install location is not a directory key={} path={}", key, directory);
            return Optional.empty();
        }
        return Optional.of(directory);
    }

    private static MarketplaceSource directorySource(String key, Path directory) {
        return new MarketplaceSource(key, directory.toString(), "directory", null);
    }
}
