package me.neveux.ccx.tui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.neveux.ccx.model.MarketplaceSource;
import me.neveux.ccx.model.PluginMapCache;
import me.neveux.ccx.model.Settings;
import me.neveux.ccx.service.MarketplaceCloneService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.SettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MarketplaceFetchController")
class MarketplaceFetchControllerTest {

    @TempDir
    Path tempDir;

    private MarketplaceFetchController newController() {
        // The methods under test never touch the null services — only start() does.
        return new MarketplaceFetchController(null, null, null, tempDir);
    }

    private void writeCache(Instant generatedAt) throws Exception {
        var claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        var cache = new PluginMapCache(
            generatedAt,
            Map.of("plugin-a", "owner/a", "plugin-b", "owner/b"),
            Map.of());
        mapper.writeValue(claudeDir.resolve("plugin-map-cache.json").toFile(), cache);
    }

    @Nested
    @DisplayName("formatCacheInfo")
    class FormatCacheInfo {

        @Test
        @DisplayName("returns 'no cache' when no cache file is present")
        void formatCacheInfo_when_noCache_then_noCacheLabel() {
            var controller = newController();

            assertThat(controller.formatCacheInfo()).isEqualTo("💡 no cache");
        }

        @Test
        @DisplayName("reports count + expired flag when generatedAt is past the TTL")
        void formatCacheInfo_when_expired_then_expiredLabelAndCount() throws Exception {
            writeCache(Instant.now().minus(Duration.ofDays(8)));
            var controller = newController();
            controller.loadCacheInfo();

            assertThat(controller.formatCacheInfo())
                .isEqualTo("💡 2 plugins cached · expired");
        }

        @Test
        @DisplayName("reports a plain count with no TTL countdown when fresh")
        void formatCacheInfo_when_fresh_then_countWithoutCountdown() throws Exception {
            writeCache(Instant.now().minus(Duration.ofDays(2)));
            var controller = newController();
            controller.loadCacheInfo();

            assertThat(controller.formatCacheInfo())
                .as("the footer no longer shows a days/hours TTL countdown")
                .isEqualTo("💡 2 plugins cached")
                .doesNotContain("remaining")
                .doesNotContain("d ")
                .doesNotContain("h remaining");
        }

        @Test
        @DisplayName("is expired just over the 7-day TTL boundary")
        void formatCacheInfo_when_justOverSevenDays_then_expired() throws Exception {
            writeCache(Instant.now().minus(Duration.ofDays(7)).minus(Duration.ofHours(1)));
            var controller = newController();
            controller.loadCacheInfo();

            assertThat(controller.formatCacheInfo()).endsWith("· expired");
        }

        @Test
        @DisplayName("is not expired just under the 7-day TTL boundary")
        void formatCacheInfo_when_justUnderSevenDays_then_notExpired() throws Exception {
            writeCache(Instant.now().minus(Duration.ofDays(6)).minus(Duration.ofHours(23)));
            var controller = newController();
            controller.loadCacheInfo();

            assertThat(controller.formatCacheInfo())
                .isEqualTo("💡 2 plugins cached")
                .doesNotContain("expired");
        }
    }

    @Nested
    @DisplayName("consumeRefreshPending")
    class ConsumeRefreshPending {

        // refreshPending is only flipped to true by the fetch thread inside start(),
        // which we deliberately do not run. The reachable contract on a fresh
        // controller is that it stays false; the true->false latch transition is
        // covered indirectly via the fetch thread.
        @Test
        @DisplayName("returns false on a fresh controller and stays false on repeat calls")
        void consumeRefreshPending_when_fresh_then_alwaysFalse() {
            var controller = newController();

            assertThat(controller.consumeRefreshPending()).isFalse();
            assertThat(controller.consumeRefreshPending()).isFalse();
        }
    }

    @Nested
    @DisplayName("setStatus / statusActive")
    class Status {

        @Test
        @DisplayName("activates the status line with the given message for a positive TTL")
        void setStatus_when_positiveSeconds_then_active() {
            var controller = newController();

            controller.setStatus("hi", 4);

            assertThat(controller.statusActive()).isTrue();
            assertThat(controller.statusMessage()).isEqualTo("hi");
        }

        @Test
        @DisplayName("is inactive immediately for a zero-second TTL")
        void setStatus_when_zeroSeconds_then_inactive() {
            var controller = newController();

            controller.setStatus("x", 0);

            assertThat(controller.statusActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("start() — the background fetch daemon")
    class Start {

        private static final long DEADLINE_NANOS = Duration.ofSeconds(5).toNanos();

        // A SettingsService whose read(Path) always returns the same canned settings,
        // so start()'s project + global reads both see the configured sources.
        private SettingsService settingsReturning(Settings settings) {
            return new SettingsService() {
                @Override
                public Settings read(Path projectRoot) {
                    return settings;
                }
            };
        }

        // A clone service that records whether refresh/ensureLocal were hit per source,
        // and can be told to throw to exercise the per-source error-swallow path.
        private static final class RecordingCloneService extends MarketplaceCloneService {
            volatile boolean ensureLocalCalled = false;
            volatile boolean refreshCalled = false;
            volatile boolean throwOnInvoke = false;

            @Override
            public Path ensureLocal(MarketplaceSource source) throws IOException {
                ensureLocalCalled = true;
                if (throwOnInvoke) throw new IOException("boom from ensureLocal");
                return Path.of(System.getProperty("java.io.tmpdir"));
            }

            @Override
            public void refresh(MarketplaceSource source) throws IOException {
                refreshCalled = true;
                if (throwOnInvoke) throw new IOException("boom from refresh");
            }
        }

        // Deterministically wait for the daemon fetch thread to finish by joining it by name.
        // Polling controller.fetching() races: the thread may not have set fetching=true yet
        // (or may already be done) when the poll loop first reads it. Joining the actual
        // Thread is race-free.
        private void awaitFetchDone(String threadName) throws InterruptedException {
            var deadline = System.nanoTime() + DEADLINE_NANOS;
            Thread fetchThread = findThread(threadName);
            // The thread is started synchronously inside start(), so it exists by the time
            // start() returns; retry briefly only to cover any scheduling slack.
            while (fetchThread == null && System.nanoTime() < deadline) {
                Thread.sleep(5);
                fetchThread = findThread(threadName);
            }
            if (fetchThread != null) {
                var remainingMillis = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
                fetchThread.join(remainingMillis);
            }
        }

        private Thread findThread(String name) {
            return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> name.equals(t.getName()))
                .findFirst()
                .orElse(null);
        }

        private Settings settingsWith(MarketplaceSource... sources) {
            return new Settings().withExtraKnownMarketplaces(List.of(sources));
        }

        private MarketplaceSource githubSource() {
            return new MarketplaceSource("remote", null, "github", "owner/repo");
        }

        @Test
        @DisplayName("happy path: completes, sets cache info, latches refreshPending once, status reports plugin count")
        void start_when_buildMapReturnsEntries_then_publishesCountAndLatchesOnce() throws Exception {
            var clone = new RecordingCloneService();
            var pluginMap = new PluginMapService() {
                @Override
                public Map<String, String> buildMap(List<MarketplaceSource> sources) {
                    return Map.of("a", "owner/a", "b", "owner/b", "c", "owner/c");
                }
            };
            var controller = new MarketplaceFetchController(
                settingsReturning(settingsWith(githubSource())), clone, pluginMap, tempDir);

            controller.start("test-fetch-happy", false);
            awaitFetchDone("test-fetch-happy");

            assertThat(controller.fetching())
                .as("fetch thread must have flipped fetching back to false")
                .isFalse();
            assertThat(controller.consumeRefreshPending())
                .as("refreshPending latches true exactly once after a fetch")
                .isTrue();
            assertThat(controller.consumeRefreshPending())
                .as("second consume must return false — the latch reset")
                .isFalse();
            assertThat(controller.formatCacheInfo())
                .as("cacheBuiltAt was set, so it is no longer the 'no cache' label, and reports 3 plugins")
                .isNotEqualTo("💡 no cache")
                .contains("3 plugins");
            assertThat(controller.statusMessage())
                .as("status line reports the fetched count")
                .contains("found 3 plugins");
        }

        @Test
        @DisplayName("non-forced github source goes through ensureLocal, not refresh")
        void start_when_notForced_then_usesEnsureLocal() throws Exception {
            var clone = new RecordingCloneService();
            var pluginMap = new PluginMapService() {
                @Override
                public Map<String, String> buildMap(List<MarketplaceSource> sources) {
                    return Map.of("a", "owner/a");
                }
            };
            var controller = new MarketplaceFetchController(
                settingsReturning(settingsWith(githubSource())), clone, pluginMap, tempDir);

            controller.start("test-fetch-ensure", false);
            awaitFetchDone("test-fetch-ensure");

            assertThat(clone.ensureLocalCalled)
                .as("forceRefresh=false must take the ensureLocal branch")
                .isTrue();
            assertThat(clone.refreshCalled)
                .as("forceRefresh=false must NOT call refresh")
                .isFalse();
        }

        @Test
        @DisplayName("forced refresh on a github source goes through refresh, not ensureLocal")
        void start_when_forced_then_usesRefresh() throws Exception {
            var clone = new RecordingCloneService();
            var pluginMap = new PluginMapService() {
                @Override
                public Map<String, String> buildMap(List<MarketplaceSource> sources) {
                    return Map.of("a", "owner/a");
                }
            };
            var controller = new MarketplaceFetchController(
                settingsReturning(settingsWith(githubSource())), clone, pluginMap, tempDir);

            controller.start("test-fetch-refresh", true);
            awaitFetchDone("test-fetch-refresh");

            assertThat(clone.refreshCalled)
                .as("forceRefresh=true must take the refresh branch")
                .isTrue();
            assertThat(clone.ensureLocalCalled)
                .as("forceRefresh=true must NOT call ensureLocal")
                .isFalse();
        }

        @Test
        @DisplayName("when buildMap throws: fetching flips false, latch holds, and the failure is reported instead of a fake success")
        void start_when_buildMapThrows_then_failureIsReported() throws Exception {
            var clone = new RecordingCloneService();
            var pluginMap = new PluginMapService() {
                @Override
                public Map<String, String> buildMap(List<MarketplaceSource> sources) {
                    throw new RuntimeException("buildMap exploded");
                }
            };
            var controller = new MarketplaceFetchController(
                settingsReturning(settingsWith(githubSource())), clone, pluginMap, tempDir);

            controller.start("test-fetch-throw", false);
            awaitFetchDone("test-fetch-throw");

            assertThat(controller.fetching())
                .as("the finally block must flip fetching false even when buildMap throws")
                .isFalse();
            assertThat(controller.consumeRefreshPending())
                .as("the finally block must latch refreshPending even when buildMap throws")
                .isTrue();
            assertThat(controller.statusMessage())
                .as("a failed fetch must name the failure, not claim it found 0 plugins")
                .contains("marketplace fetch failed")
                .contains("buildMap exploded")
                .doesNotContain("found 0 plugins");
            assertThat(controller.formatCacheInfo())
                .as("a failed fetch must not stamp a fresh cache of 0 plugins")
                .isEqualTo("💡 no cache");
        }

        @Test
        @DisplayName("a failed fetch with a message-less exception still names the exception type")
        void start_when_failureHasNoMessage_then_statusNamesTheType() throws Exception {
            var clone = new RecordingCloneService();
            var pluginMap = new PluginMapService() {
                @Override
                public Map<String, String> buildMap(List<MarketplaceSource> sources) {
                    throw new IllegalStateException();
                }
            };
            var controller = new MarketplaceFetchController(
                settingsReturning(settingsWith(githubSource())), clone, pluginMap, tempDir);

            controller.start("test-fetch-nomsg", false);
            awaitFetchDone("test-fetch-nomsg");

            assertThat(controller.statusMessage())
                .as("a null message must not surface as the string \"null\" in the footer")
                .contains("IllegalStateException")
                .doesNotContain("null");
        }

        @Test
        @DisplayName("a second start() while a fetch is in flight is refused, so two threads never share one clone")
        void start_when_alreadyFetching_then_secondCallIsRefused() throws Exception {
            var clone = new RecordingCloneService();
            var release = new java.util.concurrent.CountDownLatch(1);
            var buildMapCalls = new java.util.concurrent.atomic.AtomicInteger();
            var pluginMap = new PluginMapService() {
                @Override
                public Map<String, String> buildMap(List<MarketplaceSource> sources) {
                    buildMapCalls.incrementAndGet();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Map.of("a", "owner/a");
                }
            };
            var controller = new MarketplaceFetchController(
                settingsReturning(settingsWith(githubSource())), clone, pluginMap, tempDir);

            var first = controller.start("test-fetch-guard-1", true);
            var second = controller.start("test-fetch-guard-2", true);
            release.countDown();
            awaitFetchDone("test-fetch-guard-1");

            assertThat(first).as("the first call claims the guard").isTrue();
            assertThat(second)
                .as("the guard is claimed on the caller's thread, so a repeated keypress cannot start a second fetch")
                .isFalse();
            assertThat(buildMapCalls)
                .as("only one fetch may ever run against the same clone")
                .hasValue(1);
            assertThat(controller.start("test-fetch-guard-3", true))
                .as("once the fetch finished the guard is released again")
                .isTrue();
            awaitFetchDone("test-fetch-guard-3");
        }

        @Test
        @DisplayName("per-source error is swallowed: an ensureLocal failure does not abort the fetch")
        void start_when_ensureLocalThrows_then_fetchStillCompletes() throws Exception {
            var clone = new RecordingCloneService();
            clone.throwOnInvoke = true;
            var pluginMap = new PluginMapService() {
                @Override
                public Map<String, String> buildMap(List<MarketplaceSource> sources) {
                    return Map.of("a", "owner/a");
                }
            };
            var controller = new MarketplaceFetchController(
                settingsReturning(settingsWith(githubSource())), clone, pluginMap, tempDir);

            controller.start("test-fetch-source-err", false);
            awaitFetchDone("test-fetch-source-err");

            assertThat(clone.ensureLocalCalled)
                .as("the per-source clone was attempted")
                .isTrue();
            assertThat(controller.fetching())
                .as("a per-source error is swallowed; the loop continues and buildMap still runs")
                .isFalse();
            assertThat(controller.consumeRefreshPending())
                .as("the fetch completed normally despite the per-source error")
                .isTrue();
        }

        @Test
        @DisplayName("no sources: buildMap is never called, fetchedCount stays 0, finally still latches")
        void start_when_noSources_then_buildMapSkippedButFinallyRuns() throws Exception {
            var clone = new RecordingCloneService();
            var buildMapInvoked = new java.util.concurrent.atomic.AtomicBoolean(false);
            var pluginMap = new PluginMapService() {
                @Override
                public Map<String, String> buildMap(List<MarketplaceSource> sources) {
                    buildMapInvoked.set(true);
                    return Map.of();
                }
            };
            var controller = new MarketplaceFetchController(
                settingsReturning(new Settings().withExtraKnownMarketplaces(null)), clone, pluginMap, tempDir);

            controller.start("test-fetch-empty", false);
            awaitFetchDone("test-fetch-empty");

            assertThat(buildMapInvoked)
                .as("with no sources, buildMap must not be called")
                .isFalse();
            assertThat(controller.fetching()).isFalse();
            assertThat(controller.consumeRefreshPending())
                .as("finally still latches refreshPending even with no sources")
                .isTrue();
        }
    }
}
