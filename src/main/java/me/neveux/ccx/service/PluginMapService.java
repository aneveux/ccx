package me.neveux.ccx.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.neveux.ccx.config.ObjectMapperConfig;
import me.neveux.ccx.model.MarketplaceManifest;
import me.neveux.ccx.model.MarketplaceSource;
import me.neveux.ccx.model.PluginMapCache;
import me.neveux.ccx.model.Settings;
import me.neveux.ccx.util.FileUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds and caches a map of plugin short-names to qualified names
 * ({@code pluginName@marketplaceKey}) by scanning all configured marketplace sources.
 *
 * <p>Plugin discovery first looks for a {@code .claude-plugin/marketplace.json} manifest
 * in each marketplace directory; if absent, it falls back to a directory scan where each
 * non-hidden subdirectory is treated as a plugin. The map is written to
 * {@code .claude/plugin-map-cache.json} next to {@code settings.json} and is reused for
 * {@link MarketplaceCloneService#CACHE_TTL} before a rebuild is triggered.
 *
 * <p>Callers on the UI render thread must use {@link #buildMapNoFetch} — it reads
 * existing local clones without network access. The background daemon thread calls
 * {@link #buildMap(java.util.List)} which may clone/pull from GitHub.
 */
@ApplicationScoped
public class PluginMapService {

    private static final Logger log = LoggerFactory.getLogger(PluginMapService.class);

    private final ObjectMapper mapper;
    private final MarketplaceCloneService marketplaceCloneService;
    private final ClaudeMarketplaceRegistryService claudeMarketplaceRegistryService;
    private volatile Map<String, String> lastDescriptions = Map.of();

    public PluginMapService() {
        this.mapper = ObjectMapperConfig.defaultJson();
        this.marketplaceCloneService = new MarketplaceCloneService();
        this.claudeMarketplaceRegistryService = new ClaudeMarketplaceRegistryService();
    }

    @Inject
    public PluginMapService(@Named("jsonMapper") ObjectMapper mapper,
                            MarketplaceCloneService marketplaceCloneService,
                            ClaudeMarketplaceRegistryService claudeMarketplaceRegistryService) {
        this.mapper = mapper;
        this.marketplaceCloneService = marketplaceCloneService;
        this.claudeMarketplaceRegistryService = claudeMarketplaceRegistryService;
    }

    /**
     * Resolves a plugin short-name to its qualified {@code name@marketplace} form using the
     * cached plugin map for {@code projectRoot}. Rebuilds and persists the cache when it is
     * absent or stale.
     *
     * @param shortName   the unqualified plugin name to resolve
     * @param projectRoot root of the project (cache file lives at {@code .claude/plugin-map-cache.json})
     * @param settings    the project settings containing marketplace sources
     * @return the qualified name if found, or an empty optional if the plugin is not in any known marketplace
     */
    public Optional<String> resolve(String shortName, Path projectRoot, Settings settings) {
        var cacheFile = projectRoot.resolve(".claude/plugin-map-cache.json");
        var cache = loadCache(cacheFile);

        if (cache != null && isFresh(cache)) {
            log.debug("plugin-map cache hit shortName={}", shortName);
            this.lastDescriptions = cache.descriptions() != null ? cache.descriptions() : Map.of();
            return Optional.ofNullable(cache.entries().get(shortName));
        }

        log.debug("plugin-map cache miss or stale — rebuilding shortName={}", shortName);
        var entries = buildMap(settings);
        writeCache(cacheFile, entries);
        return Optional.ofNullable(entries.get(shortName));
    }

    /**
     * Builds the full plugin map from the marketplace sources declared in {@code settings}.
     * May perform network operations (git clone/pull) to ensure sources are local.
     *
     * @param settings project settings containing the {@code extraKnownMarketplaces} list
     * @return map of short plugin name to qualified {@code name@marketplace} name; never null
     */
    public Map<String, String> buildMap(Settings settings) {
        if (settings.extraKnownMarketplaces() == null) return Map.of();
        return buildMap(settings.extraKnownMarketplaces());
    }

    /**
     * Builds the plugin map from the merged marketplace sources of {@code project} and
     * {@code global} settings, with project sources taking precedence over global ones
     * that share the same key.
     *
     * @param project project-level settings
     * @param global  global settings (typically from {@code ~/.claude/settings.json})
     * @return map of short plugin name to qualified {@code name@marketplace} name; never null
     */
    public Map<String, String> buildMap(Settings project, Settings global) {
        var merged = mergeSources(project, global);
        return merged.isEmpty() ? Map.of() : buildMap(merged);
    }

    /**
     * Merges project and global marketplace sources, deduplicating by key —
     * project entries take precedence over global ones sharing the same key.
     */
    public static List<MarketplaceSource> mergeSources(Settings project, Settings global) {
        var merged = new ArrayList<MarketplaceSource>();
        if (project.extraKnownMarketplaces() != null) merged.addAll(project.extraKnownMarketplaces());
        if (global.extraKnownMarketplaces() != null) {
            var projectKeys = merged.stream()
                .map(s -> s.key())
                .collect(Collectors.toSet());
            global.extraKnownMarketplaces().stream()
                .filter(s -> !projectKeys.contains(s.key()))
                .forEach(merged::add);
        }
        return merged;
    }

    /**
     * Returns the description map produced by the most recent {@link #buildMap} or
     * {@link #buildMapNoFetch} call. Keys are both the short name and the qualified name
     * for each plugin that declares a description in its manifest. Returns an empty map
     * if no build has been performed yet or the last marketplace had no descriptions.
     *
     * @return map of plugin name / qualified name to human-readable description; never null
     */
    public Map<String, String> getLastDescriptions() {
        return lastDescriptions;
    }

    /**
     * Builds the plugin map from an explicit list of sources, allowing network access to
     * clone or pull GitHub repositories.
     *
     * @param sources the marketplace sources to scan
     * @return map of short plugin name to qualified {@code name@marketplace} name; never null
     */
    public Map<String, String> buildMap(List<MarketplaceSource> sources) {
        return buildMap(sources, true, false);
    }

    /**
     * Builds the plugin map without any network access — directory sources are parsed live,
     * github sources are read from their existing local clone only (never cloned/pulled here).
     * Safe to call on the render thread; github plugins appear once a background refresh has
     * cloned them.
     * Returns qualified names (pluginName@marketplaceKey) as map keys.
     */
    public Map<String, String> buildMapNoFetch(List<MarketplaceSource> sources) {
        return buildMap(sources, false, true);
    }

    private Map<String, String> buildMap(List<MarketplaceSource> sources, boolean allowFetch, boolean useQualifiedKeys) {
        var result = new HashMap<String, String>();
        var descriptionMap = new HashMap<String, String>();
        if (sources == null) return result;

        // Claude Code's registry wins over the declared descriptor: its clones are already
        // local and already authenticated, so a private github marketplace resolves here even
        // though MarketplaceCloneService cannot clone it.
        for (var source : claudeMarketplaceRegistryService.withInstalledLocations(sources)) {
            Path dir;
            if (!allowFetch && "github".equals(source.sourceType())) {
                dir = marketplaceCloneService.localCacheDir(source.key());
                if (!Files.isDirectory(dir)) {
                    log.debug("github marketplace not yet cloned — skipping (no fetch) key={}", source.key());
                    continue;
                }
            } else {
                try {
                    dir = marketplaceCloneService.ensureLocal(source);
                } catch (IOException e) {
                    log.warn("failed to resolve marketplace source key={}", source.key(), e);
                    continue;
                }
            }
            if (!Files.isDirectory(dir)) {
                log.debug("marketplace directory not found path={} key={}", dir, source.key());
                continue;
            }
            if (!PathSecurity.resolveAndCheck(dir)) {
                log.warn("marketplace directory resolves outside user home — skipping path={} key={}", dir, source.key());
                continue;
            }
            try {
                dir = dir.toRealPath();
            } catch (IOException e) {
                log.warn("marketplace directory canonicalization failed — skipping path={} key={}", dir, source.key(), e);
                continue;
            }

            // Try marketplace.json first
            var manifestPath = dir.resolve(".claude-plugin/marketplace.json");
            if (Files.exists(manifestPath)) {
                try {
                    var manifest = mapper.readValue(manifestPath.toFile(), MarketplaceManifest.class);
                    if (manifest.plugins() != null) {
                        for (var plugin : manifest.plugins()) {
                            if (plugin == null || plugin.name() == null) continue;
                            var qualified = plugin.name() + "@" + source.key();
                            result.putIfAbsent(useQualifiedKeys ? qualified : plugin.name(), qualified);
                            if (plugin.description() != null && !plugin.description().isBlank()) {
                                descriptionMap.putIfAbsent(qualified, plugin.description());
                                descriptionMap.putIfAbsent(plugin.name(), plugin.description());
                            }
                            log.debug("discovered plugin (manifest) name={} qualified={}", plugin.name(), qualified);
                        }
                    }
                    continue; // manifest found and parsed — skip directory scan
                } catch (IOException e) {
                    log.warn("failed to parse marketplace manifest path={} — falling back to dir scan", manifestPath, e);
                }
            }

            // Fallback: directory scan
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isDirectory)
                      .filter(p -> !p.getFileName().toString().startsWith("."))
                      .forEach(subdir -> {
                          var pluginName = subdir.getFileName().toString();
                          var qualified = pluginName + "@" + source.key();
                          result.putIfAbsent(useQualifiedKeys ? qualified : pluginName, qualified);
                          log.debug("discovered plugin (scan) name={} qualified={}", pluginName, pluginName + "@" + source.key());
                      });
            } catch (IOException e) {
                log.warn("failed to scan marketplace directory path={}", dir, e);
            }
        }

        this.lastDescriptions = descriptionMap;
        return result;
    }

    private PluginMapCache loadCache(Path cacheFile) {
        if (!Files.exists(cacheFile)) return null;
        try {
            return mapper.readValue(cacheFile.toFile(), PluginMapCache.class);
        } catch (IOException e) {
            log.warn("failed to read plugin-map cache path={}", cacheFile, e);
            return null;
        }
    }

    /**
     * A cache is usable only if it carries both a timestamp inside the TTL and an entry map.
     * A file missing {@code entries} — truncated, or written by a version that omitted the
     * field — is unusable rather than fresh, so the caller rebuilds instead of dereferencing null.
     */
    private boolean isFresh(PluginMapCache cache) {
        if (cache.generatedAt() == null || cache.entries() == null) return false;
        return cache.generatedAt().isAfter(Instant.now().minus(MarketplaceCloneService.CACHE_TTL));
    }

    private void writeCache(Path cacheFile, Map<String, String> entries) {
        try {
            var cache = new PluginMapCache(Instant.now(), entries, lastDescriptions.isEmpty() ? null : lastDescriptions);
            FileUtils.atomicWrite(cacheFile, tmp -> mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), cache));
            log.debug("plugin-map cache written path={}", cacheFile);
        } catch (IOException e) {
            log.warn("failed to write plugin-map cache path={}", cacheFile, e);
        }
    }
}
