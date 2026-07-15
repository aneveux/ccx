package me.neveux.ccx.tui;

import dev.tamboui.widgets.spinner.Spinner;
import dev.tamboui.widgets.spinner.SpinnerState;
import me.neveux.ccx.config.ObjectMapperConfig;
import me.neveux.ccx.model.PluginMapCache;
import me.neveux.ccx.service.MarketplaceCloneService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Owns the TUI's background marketplace fetch: the daemon thread that clones/pulls
 * GitHub sources, the cache-info readout, and the transient status line. All mutable
 * state crosses the fetch thread / render thread boundary, so every field is
 * {@code volatile}. The status line is shared — both fetch completion and user
 * actions publish to it via {@link #setStatus}.
 */
final class MarketplaceFetchController {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceFetchController.class);

    private final SettingsService settingsService;
    private final MarketplaceCloneService marketplaceCloneService;
    private final PluginMapService pluginMapService;
    private final Path projectDir;

    private volatile String statusMessage = "";
    private volatile Instant statusExpiry = Instant.EPOCH;
    private volatile boolean refreshPending = false;
    private volatile boolean fetching = false;
    private volatile int fetchedCount = 0;
    private volatile Instant cacheBuiltAt = null;
    private volatile int cachePluginCount = 0;

    private final String[] spinnerFrames = Spinner.dots().frames();
    private final SpinnerState spinnerState = new SpinnerState();

    MarketplaceFetchController(SettingsService settingsService, MarketplaceCloneService marketplaceCloneService,
                               PluginMapService pluginMapService, Path projectDir) {
        this.settingsService = settingsService;
        this.marketplaceCloneService = marketplaceCloneService;
        this.pluginMapService = pluginMapService;
        this.projectDir = projectDir;
    }

    void start(String threadName, boolean forceRefresh) {
        var thread = new Thread(() -> {
            fetching = true;
            try {
                var projectSettings = settingsService.read(projectDir);
                var globalSettings = settingsService.read(Path.of(System.getProperty("user.home")));
                var sources = PluginMapService.mergeSources(projectSettings, globalSettings);
                for (var src : sources) {
                    if ("github".equals(src.sourceType())) {
                        try {
                            if (forceRefresh) {
                                marketplaceCloneService.refresh(src);
                            } else {
                                marketplaceCloneService.ensureLocal(src);
                            }
                            log.debug("marketplace fetch complete key={}", src.key());
                        } catch (Exception e) {
                            log.debug("marketplace fetch failed key={}", src.key(), e);
                        }
                    }
                }
                if (!sources.isEmpty()) {
                    fetchedCount = pluginMapService.buildMap(sources).size();
                }
                log.debug("background marketplace fetch done count={} rebuild pending", fetchedCount);
            } catch (Exception e) {
                log.warn("background marketplace fetch failed", e);
            } finally {
                fetching = false;
                refreshPending = true;
                statusMessage = Glyphs.OK + " found " + fetchedCount + " plugins  " + Quips.pick(Quips.ALLGOOD, fetchedCount);
                statusExpiry = Instant.now().plusSeconds(4);
                cacheBuiltAt = Instant.now();
                cachePluginCount = fetchedCount;
            }
        });
        thread.setDaemon(true);
        thread.setName(threadName);
        thread.start();
    }

    void loadCacheInfo() {
        var cacheFile = projectDir.resolve(".claude/plugin-map-cache.json");
        if (!Files.exists(cacheFile)) return;
        try {
            var mapper = ObjectMapperConfig.defaultJson();
            var cache = mapper.readValue(cacheFile.toFile(), PluginMapCache.class);
            if (cache.generatedAt() != null) {
                cacheBuiltAt = cache.generatedAt();
                cachePluginCount = cache.entries() != null ? cache.entries().size() : 0;
            }
        } catch (Exception e) {
            log.debug("failed to load plugin cache info", e);
        }
    }

    /** Publish a transient status line shown in the footer for {@code seconds}. */
    void setStatus(String message, long seconds) {
        statusMessage = message;
        statusExpiry = Instant.now().plusSeconds(seconds);
    }

    boolean fetching() {
        return fetching;
    }

    /** True while a fetch runs or a status line is still within its TTL. */
    boolean active() {
        return fetching || Instant.now().isBefore(statusExpiry);
    }

    boolean statusActive() {
        return Instant.now().isBefore(statusExpiry);
    }

    String statusMessage() {
        return statusMessage;
    }

    /** Returns {@code true} once after a fetch finishes, then resets — drives snapshot rebuild. */
    boolean consumeRefreshPending() {
        if (!refreshPending) return false;
        refreshPending = false;
        return true;
    }

    String spinnerGlyph() {
        return spinnerFrames.length == 0
            ? "⠿"
            : spinnerFrames[(int) (spinnerState.advance() % spinnerFrames.length)];
    }

    String formatCacheInfo() {
        if (cacheBuiltAt == null) return Glyphs.INFO + " no cache";
        var ttlExpiry = cacheBuiltAt.plus(MarketplaceCloneService.CACHE_TTL);
        var expired = Instant.now().isAfter(ttlExpiry);
        return Glyphs.INFO + " " + cachePluginCount + " plugins cached" + (expired ? " · expired" : "");
    }
}
