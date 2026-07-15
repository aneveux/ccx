package me.neveux.ccx.service;

import me.neveux.ccx.model.MarketplaceSource;
import me.neveux.ccx.model.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PluginMapService")
class PluginMapServiceTest {

    private PluginMapService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new PluginMapService();
    }

    // -----------------------------------------------------------------------
    // resolve() — cache miss
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when cache is absent (cache miss)")
    class WhenCacheMiss {

        @Test
        @DisplayName("should resolve plugin name from extra known marketplaces directory")
        void cacheMissBuildsFromExtraKnownMarketplaces() throws Exception {
            // marketplace dir has a "java" subdirectory — plugin "java" lives at key "my-market"
            var marketplaceDir = tempDir.resolve("marketplace");
            Files.createDirectories(marketplaceDir.resolve("java"));

            var source = new MarketplaceSource("my-market", marketplaceDir.toString(), null, null);

            var settings = new Settings()
                .withExtraKnownMarketplaces(List.of(source));

            var result = service.resolve("java", tempDir, settings);

            assertThat(result)
                .as("resolve must find 'java' in marketplace dir and return qualified name")
                .isPresent()
                .hasValue("java@my-market");
        }

        @Test
        @DisplayName("should return empty when plugin is not in any marketplace")
        void resolveReturnsEmptyForUnknownPlugin() throws Exception {
            var settings = new Settings()
                .withExtraKnownMarketplaces(List.of());

            var result = service.resolve("unknown-plugin-xyz", tempDir, settings);

            assertThat(result)
                .as("resolve must return empty for plugin not found in any marketplace")
                .isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // resolve() — cache hit
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when cache is present and fresh")
    class WhenCacheHit {

        @Test
        @DisplayName("should return cached result without rescanning after marketplace dir is removed")
        void cacheHitReturnsWithoutRescanning() throws Exception {
            var marketplaceDir = tempDir.resolve("marketplace");
            Files.createDirectories(marketplaceDir.resolve("java"));

            var source = new MarketplaceSource("my-market", marketplaceDir.toString(), null, null);

            var settings = new Settings()
                .withExtraKnownMarketplaces(List.of(source));

            // First call — builds the cache
            service.resolve("java", tempDir, settings);

            // Remove the marketplace dir to prove second call uses cache
            deleteDirectory(marketplaceDir);

            // Second call — must hit cache, not rescan
            var secondResult = service.resolve("java", tempDir, settings);

            assertThat(secondResult)
                .as("second call must return cached result even after marketplace dir is removed")
                .isPresent()
                .hasValue("java@my-market");
        }
    }

    // -----------------------------------------------------------------------
    // resolve() — stale cache
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when cache is stale (older than 7 days)")
    class WhenStaleCache {

        @Test
        @DisplayName("should rebuild cache when generatedAt is more than 7 days old")
        void staleCacheOlderThan7dIsRebuilt() throws Exception {
            var marketplaceDir = tempDir.resolve("marketplace");
            Files.createDirectories(marketplaceDir.resolve("java"));

            var source = new MarketplaceSource("my-market", marketplaceDir.toString(), null, null);

            var settings = new Settings()
                .withExtraKnownMarketplaces(List.of(source));

            // Write a stale cache file with generatedAt 8 days ago (beyond the 7-day window)
            writeStaleCacheFile(tempDir, Instant.now().minus(8, ChronoUnit.DAYS));

            // resolve must detect stale cache, rescan, and return fresh result
            var result = service.resolve("java", tempDir, settings);

            assertThat(result)
                .as("stale cache must be rebuilt from marketplace scan")
                .isPresent()
                .hasValue("java@my-market");
        }
    }

    // -----------------------------------------------------------------------
    // resolve() — corrupt cache
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when cache file is corrupt")
    class WhenCorruptCache {

        @Test
        @DisplayName("should rebuild the cache without throwing when cache JSON is invalid")
        void resolve_rebuildsCacheWhenCacheFileCorrupt() throws Exception {
            var marketplaceDir = tempDir.resolve("marketplace");
            Files.createDirectories(marketplaceDir.resolve("java"));

            var source = new MarketplaceSource("my-market", marketplaceDir.toString(), null, null);

            var settings = new Settings()
                .withExtraKnownMarketplaces(List.of(source));

            // Write a corrupt cache file
            var claudeDir = tempDir.resolve(".claude");
            Files.createDirectories(claudeDir);
            Files.writeString(claudeDir.resolve("plugin-map-cache.json"), "NOT_VALID_JSON{{{");

            // resolve must survive the corrupt file and rebuild from scratch
            var result = service.resolve("java", tempDir, settings);

            assertThat(result)
                .as("resolve must succeed by rebuilding cache after a corrupt cache file")
                .isPresent()
                .hasValue("java@my-market");
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private void writeStaleCacheFile(Path projectRoot, Instant generatedAt) throws Exception {
        var claudeDir = projectRoot.resolve(".claude");
        Files.createDirectories(claudeDir);
        var cacheFile = claudeDir.resolve("plugin-map-cache.json");
        // Write minimal valid cache JSON with stale generatedAt and empty entries
        var json = "{\"generatedAt\":\"" + generatedAt.toString() + "\",\"entries\":{}}";
        Files.writeString(cacheFile, json, StandardCharsets.UTF_8);
    }

    private void deleteDirectory(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .forEach(p -> {
                      try { Files.delete(p); } catch (Exception e) { /* ignore */ }
                  });
        }
    }

    // -----------------------------------------------------------------------
    // Plan 041 W4A — buildMapNoFetch()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("buildMapNoFetch()")
    class BuildMapNoFetch {

        @Test
        @DisplayName("github source with no local clone returns empty map (no network I/O)")
        void buildMapNoFetch_githubSourceNotYetCloned_returnsEmpty(@TempDir Path tempDir) {
            // A github source whose local cache dir does NOT exist
            var source = new MarketplaceSource("my-mkt", null, "github", "owner/repo");
            var result = service.buildMapNoFetch(List.of(source));
            assertThat(result)
                .as("github source with no local clone must return empty — no fetch, no exception")
                .isEmpty();
        }

        @Test
        @DisplayName("directory source is scanned live by buildMapNoFetch")
        void buildMapNoFetch_directorySource_returnsPlugins(@TempDir Path marketplaceDir) throws Exception {
            Files.createDirectories(marketplaceDir.resolve("java-stack"));
            var source = new MarketplaceSource("my-mkt", marketplaceDir.toString(), "directory", null);
            var result = service.buildMapNoFetch(List.of(source));
            assertThat(result)
                .as("directory source must be scanned live by buildMapNoFetch")
                .containsKey("java-stack@my-mkt");
        }

        @Test
        @DisplayName("github source with existing local clone returns its plugins without fetching")
        void buildMapNoFetch_githubSourceWithClone_returnsPlugins(@TempDir Path tempDir) throws Exception {
            // Arrange: create a fake local clone in tempDir
            Files.createDirectories(tempDir.resolve("cool-plugin"));

            // Wire PluginMapService with a MarketplaceCloneService that returns tempDir for our key
            var cloneService = new MarketplaceCloneService() {
                @Override
                public Path localCacheDir(String key) {
                    return "cloned-mkt".equals(key) ? tempDir : super.localCacheDir(key);
                }
            };
            var svc = new PluginMapService(me.neveux.ccx.config.ObjectMapperConfig.defaultJson(), cloneService);

            var source = new MarketplaceSource("cloned-mkt", null, "github", "owner/repo");
            var result = svc.buildMapNoFetch(List.of(source));

            assertThat(result)
                .as("github source with local clone dir must return plugins without fetching")
                .containsKey("cool-plugin@cloned-mkt");
        }
    }

    // -----------------------------------------------------------------------
    // Plan 040 Item 24 — mergeSources()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("mergeSources()")
    class MergeSources {

        @Test
        @DisplayName("project entry wins when project and global have the same source key")
        void mergeSources_projectWinsOverGlobal() {
            var pathA = "/home/user/marketplace-a";
            var pathB = "/home/user/marketplace-b";
            var projectSource = new MarketplaceSource("k1", pathA, "directory", null);
            var globalSource = new MarketplaceSource("k1", pathB, "directory", null);

            var project = new Settings().withExtraKnownMarketplaces(List.of(projectSource));
            var global = new Settings().withExtraKnownMarketplaces(List.of(globalSource));

            var result = PluginMapService.mergeSources(project, global);

            assertThat(result)
                .as("merged result must contain exactly one entry for key k1")
                .hasSize(1);
            assertThat(result.get(0).path())
                .as("project source must win over global source for the same key")
                .isEqualTo(pathA);
        }

        @Test
        @DisplayName("disjoint project and global sources are both included in the result")
        void mergeSources_disjointMerge() {
            var projectSource = new MarketplaceSource("k1", "/a", "directory", null);
            var globalSource = new MarketplaceSource("k2", "/b", "directory", null);

            var project = new Settings().withExtraKnownMarketplaces(List.of(projectSource));
            var global = new Settings().withExtraKnownMarketplaces(List.of(globalSource));

            var result = PluginMapService.mergeSources(project, global);

            assertThat(result)
                .as("merged result must contain both disjoint keys")
                .hasSize(2)
                .extracting(MarketplaceSource::key)
                .containsExactlyInAnyOrder("k1", "k2");
        }

        @Test
        @DisplayName("returns empty list without NPE when both project and global have null extraKnownMarketplaces")
        void mergeSources_bothNull() {
            var project = new Settings();  // extraKnownMarketplaces is null
            var global = new Settings();   // extraKnownMarketplaces is null

            var result = PluginMapService.mergeSources(project, global);

            assertThat(result)
                .as("mergeSources with both null extraKnownMarketplaces must return an empty list without NPE")
                .isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // buildMap() reads .claude-plugin/marketplace.json when present
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when .claude-plugin/marketplace.json is present")
    class WhenMarketplaceManifestPresent {

        @Test
        @DisplayName("buildMap() returns individual plugin names listed in marketplace.json, not only subdirs")
        void buildMap_readsPluginNamesFromMarketplaceJson() throws Exception {
            // Arrange: marketplace dir with a manifest listing specific plugin names
            var marketplaceDir = tempDir.resolve("marketplace");
            Files.createDirectories(marketplaceDir.resolve(".claude-plugin"));

            // The manifest lists individual plugins as objects (real Claude Code schema) —
            // these are the canonical names to use instead of scanning subdirs
            var manifestJson = """
                {
                  "plugins": [
                    {"name": "antoine-voice"},
                    {"name": "qf-inbox"},
                    {"name": "bark"}
                  ]
                }
                """;
            Files.writeString(marketplaceDir.resolve(".claude-plugin/marketplace.json"), manifestJson);

            var source = new MarketplaceSource("ccx-plugins", marketplaceDir.toString(), null, null);

            var result = service.buildMap(List.of(source));

            assertThat(result)
                .as("buildMap must include plugins listed in marketplace.json")
                .containsKey("antoine-voice")
                .containsKey("qf-inbox")
                .containsKey("bark");

            assertThat(result.get("antoine-voice"))
                .as("qualified name for antoine-voice must use the source key")
                .isEqualTo("antoine-voice@ccx-plugins");
        }

        @Test
        @DisplayName("buildMap() parses real-shaped manifest with extra fields (source/author/category)")
        void buildMap_parsesRealManifestWithExtraFields() throws Exception {
            // Regression guard: real .claude-plugin/marketplace.json files use an array of
            // OBJECTS with name/description plus a polymorphic "source" (string OR object),
            // "author", "category", "homepage". The old List<String> model threw on this and
            // silently fell back to a root dir scan — leaving the plugin map empty.
            var marketplaceDir = tempDir.resolve("marketplace");
            Files.createDirectories(marketplaceDir.resolve(".claude-plugin"));

            var manifestJson = """
                {
                  "$schema": "https://anthropic.com/claude-code/marketplace.schema.json",
                  "name": "claude-garden",
                  "owner": {"name": "Antoine Neveux", "email": "antoine@neveux.me"},
                  "metadata": {"description": "Personal marketplace", "version": "2.12.0"},
                  "plugins": [
                    {
                      "name": "bark",
                      "description": "Bash development plugin with quality validation agents.",
                      "source": "./plugins/bark",
                      "strict": false
                    },
                    {
                      "name": "api-security",
                      "description": "Automate API security in Claude Code.",
                      "author": {"name": "42Crunch"},
                      "category": "security",
                      "source": {
                        "source": "git-subdir",
                        "url": "https://github.com/42Crunch-AI/claude-plugins.git",
                        "path": "plugins/api-security-testing",
                        "ref": "v1.5.5"
                      },
                      "homepage": "https://42crunch.com"
                    }
                  ]
                }
                """;
            Files.writeString(marketplaceDir.resolve(".claude-plugin/marketplace.json"), manifestJson);

            var source = new MarketplaceSource("claude-garden", marketplaceDir.toString(), null, null);

            var result = service.buildMap(List.of(source));

            assertThat(result)
                .as("real-shaped object-array manifest must yield plugin names, not an empty map")
                .containsEntry("bark", "bark@claude-garden")
                .containsEntry("api-security", "api-security@claude-garden");

            assertThat(service.getLastDescriptions())
                .as("descriptions must be extracted from the per-plugin description field")
                .containsEntry("bark@claude-garden", "Bash development plugin with quality validation agents.")
                .containsEntry("api-security@claude-garden", "Automate API security in Claude Code.");
        }

        @Test
        @DisplayName("buildMap() falls back to directory scan when .claude-plugin/marketplace.json is absent")
        void buildMap_fallsBackToDirectoryScanWhenManifestAbsent() throws Exception {
            var marketplaceDir = tempDir.resolve("marketplace");
            Files.createDirectories(marketplaceDir.resolve("java"));
            Files.createDirectories(marketplaceDir.resolve("quarkus"));
            // No marketplace.json — fallback to dir scan

            var source = new MarketplaceSource("my-stack", marketplaceDir.toString(), null, null);

            var result = service.buildMap(List.of(source));

            assertThat(result)
                .as("fallback dir scan must discover plugins from subdirectories")
                .containsKey("java")
                .containsKey("quarkus");
        }

        @Test
        @DisplayName("PluginMapCache.descriptions is populated from marketplace.json descriptions field")
        void buildMap_populatesDescriptionsFromManifest() throws Exception {
            var marketplaceDir = tempDir.resolve("marketplace");
            Files.createDirectories(marketplaceDir.resolve(".claude-plugin"));

            var manifestJson = """
                {
                  "plugins": [
                    {
                      "name": "antoine-voice",
                      "description": "Rewrite text in Antoine's personal voice"
                    }
                  ]
                }
                """;
            Files.writeString(marketplaceDir.resolve(".claude-plugin/marketplace.json"), manifestJson);
            Files.createDirectories(marketplaceDir.resolve("antoine-voice"));

            var source = new MarketplaceSource("my-market", marketplaceDir.toString(), null, null);

            var settings = new Settings()
                .withExtraKnownMarketplaces(List.of(source));

            var projectRoot = tempDir.resolve("project");
            Files.createDirectories(projectRoot.resolve(".claude"));

            service.resolve("antoine-voice", projectRoot, settings);

            // Read the written cache file as raw JSON to verify descriptions field is present
            // (PluginMapCache.descriptions is not yet in the model — production code adds it)
            var cacheFile = projectRoot.resolve(".claude/plugin-map-cache.json");
            assertThat(cacheFile)
                .as("cache file must be written after resolve()")
                .exists();

            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var rootNode = mapper.readTree(cacheFile.toFile());

            assertThat(rootNode.has("descriptions"))
                .as("cache JSON must contain a 'descriptions' field when marketplace.json has descriptions")
                .isTrue();

            assertThat(rootNode.path("descriptions").path("antoine-voice").asText())
                .as("descriptions.antoine-voice must equal the value from marketplace.json")
                .isEqualTo("Rewrite text in Antoine's personal voice");
        }
    }
}
