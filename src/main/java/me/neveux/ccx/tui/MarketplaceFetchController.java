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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the TUI's background marketplace fetch: the daemon thread that clones/pulls
 * GitHub sources, the cache-info readout, and the transient status line. All mutable
 * state crosses the fetch thread / render thread boundary, so every field is
 * {@code volatile}. The status line is shared — both fetch completion and user
 * actions publish to it via {@link #setStatus}.
 *
 * <p>Not {@code final} so {@code CcxAppTest} can substitute a subclass that signals a pending
 * refresh without running a real fetch.
 */
class MarketplaceFetchController {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceFetchController.class);

    private final SettingsService settingsService;
    private final MarketplaceCloneService marketplaceCloneService;
    private final PluginMapService pluginMapService;
    private final Path projectDir;

    private volatile String statusMessage = "";
    private volatile Instant statusExpiry = Instant.EPOCH;
    private volatile boolean refreshPending = false;
    private final AtomicBoolean fetching = new AtomicBoolean(false);
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

    /**
     * Starts the background fetch, unless one is already in flight. The guard is claimed on the
     * caller's thread, so a held-down refresh key cannot land two fetches in the same clone.
     *
     * @return {@code true} if this call started a fetch, {@code false} if one was already running
     */
    boolean start(String threadName, boolean forceRefresh) {
        if (!fetching.compareAndSet(false, true)) {
            log.debug("marketplace fetch already in flight — ignoring request name={}", threadName);
            return false;
        }
        var thread = new Thread(() -> runFetch(forceRefresh));
        thread.setDaemon(true);
        thread.setName(threadName);
        thread.start();
        return true;
    }

    private void runFetch(boolean forceRefresh) {
        var succeeded = false;
        String failure = null;
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
            succeeded = true;
            log.debug("background marketplace fetch done count={} rebuild pending", fetchedCount);
        } catch (Exception e) {
            failure = describe(e);
            log.warn("background marketplace fetch failed", e);
        } finally {
            if (succeeded) {
                statusMessage = Glyphs.OK + " found " + fetchedCount + " plugins  " + Quips.pick(Quips.ALLGOOD, fetchedCount);
                cacheBuiltAt = Instant.now();
                cachePluginCount = fetchedCount;
            } else {
                // Leave cacheBuiltAt alone: stamping it would advertise a fresh cache of 0 plugins.
                statusMessage = Glyphs.WARNING + " marketplace fetch failed: " + failure;
            }
            statusExpiry = Instant.now().plusSeconds(4);
            refreshPending = true;
            fetching.set(false);
        }
    }

    /** The first line of an exception's message, or its type when it carries no message. */
    private static String describe(Exception e) {
        var message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.lines().findFirst().orElse(message);
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
        return fetching.get();
    }

    /** True while a fetch runs or a status line is still within its TTL. */
    boolean active() {
        return fetching.get() || Instant.now().isBefore(statusExpiry);
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
