package me.neveux.ccx.service;

import me.neveux.ccx.model.MarketplaceSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MarketplaceCloneService")
class MarketplaceCloneServiceTest {

    @TempDir
    Path tempDir;

    class TestableService extends MarketplaceCloneService {
        boolean cloneCalled = false;
        boolean pullCalled = false;
        String lastClonedRepo = null;

        @Override
        public Path localCacheDir(String key) {
            return tempDir.resolve(key);
        }

        @Override
        protected void clone(String githubRepo, Path targetDir) throws IOException {
            cloneCalled = true;
            lastClonedRepo = githubRepo;
            Files.createDirectories(targetDir);
        }

        @Override
        protected void pull(Path dir) throws IOException {
            pullCalled = true;
        }
    }

    static MarketplaceSource dirSource(String key, String path) {
        return new MarketplaceSource(key, path, "directory", null);
    }

    static MarketplaceSource githubSource(String key, String repo) {
        return new MarketplaceSource(key, null, "github", repo);
    }

    @Nested
    @DisplayName("ensureLocal()")
    class EnsureLocal {

        @Test
        void ensureLocal_directory_returnsPathUnchanged() throws IOException {
            var svc = new TestableService();
            var source = dirSource("mkt", tempDir.toString());
            var result = svc.ensureLocal(source);
            assertThat(result).isEqualTo(Path.of(tempDir.toString()));
            assertThat(svc.cloneCalled).isFalse();
        }

        @Test
        void ensureLocal_github_clonesOnFirstUse() throws IOException {
            var svc = new TestableService();
            var source = githubSource("myplugins", "org/myplugins");
            var cacheDir = svc.localCacheDir("myplugins");

            var result = svc.ensureLocal(source);

            assertThat(svc.cloneCalled).isTrue();
            assertThat(svc.lastClonedRepo).isEqualTo("org/myplugins");
            assertThat(result).isEqualTo(cacheDir);
            assertThat(Files.exists(cacheDir.resolve(".ccx-fetched"))).isTrue();
        }

        @Test
        void ensureLocal_github_pullsWhenCacheStale() throws IOException {
            var svc = new TestableService();
            var source = githubSource("myplugins", "org/myplugins");
            var cacheDir = svc.localCacheDir("myplugins");
            Files.createDirectories(cacheDir);
            Files.writeString(cacheDir.resolve(".ccx-repo"), "org/myplugins\n");
            Files.writeString(cacheDir.resolve(".ccx-fetched"),
                Instant.now().minus(169, ChronoUnit.HOURS).toString());

            var result = svc.ensureLocal(source);

            assertThat(svc.pullCalled).isTrue();
            assertThat(svc.cloneCalled).isFalse();
            assertThat(result).isEqualTo(cacheDir);
        }

        @Test
        @DisplayName("a fresh cache cloned from another repo is discarded and re-cloned")
        void ensureLocal_github_cacheFromDifferentRepo_isDiscarded() throws IOException {
            var svc = new TestableService();
            var cacheDir = svc.localCacheDir("myplugins");
            Files.createDirectories(cacheDir);
            Files.writeString(cacheDir.resolve(".ccx-repo"), "attacker/evil\n");
            Files.writeString(cacheDir.resolve(".ccx-fetched"), Instant.now().toString());
            Files.writeString(cacheDir.resolve("poisoned.txt"), "content from the wrong repo");

            var result = svc.ensureLocal(githubSource("myplugins", "org/myplugins"));

            assertThat(svc.cloneCalled)
                .as("a cache entry cloned from another repository must not be reused, however fresh")
                .isTrue();
            assertThat(svc.pullCalled).isFalse();
            assertThat(svc.lastClonedRepo).isEqualTo("org/myplugins");
            assertThat(cacheDir.resolve("poisoned.txt"))
                .as("the foreign clone's content must be gone, not merged into the new clone")
                .doesNotExist();
            assertThat(result).isEqualTo(cacheDir);
        }

        @Test
        @DisplayName("a cache with no recorded provenance is discarded and re-cloned")
        void ensureLocal_github_cacheWithoutProvenance_isDiscarded() throws IOException {
            var svc = new TestableService();
            var cacheDir = svc.localCacheDir("myplugins");
            Files.createDirectories(cacheDir);
            Files.writeString(cacheDir.resolve(".ccx-fetched"), Instant.now().toString());

            svc.ensureLocal(githubSource("myplugins", "org/myplugins"));

            assertThat(svc.cloneCalled)
                .as("an unrecorded clone cannot be attributed to the declared repo, so it is re-cloned")
                .isTrue();
            assertThat(svc.pullCalled).isFalse();
        }

        @Test
        @DisplayName("a successful clone records the repository it came from")
        void ensureLocal_github_recordsProvenance() throws IOException {
            var svc = new TestableService();

            svc.ensureLocal(githubSource("myplugins", "org/myplugins"));

            assertThat(svc.localCacheDir("myplugins").resolve(".ccx-repo"))
                .as("the repo marker is what lets a later call detect a key rebound to another repo")
                .hasContent("org/myplugins");
        }

        @Test
        @DisplayName("a matching provenance marker keeps a fresh cache untouched")
        void ensureLocal_github_matchingProvenance_reusesFreshCache() throws IOException {
            var svc = new TestableService();
            var cacheDir = svc.localCacheDir("myplugins");
            Files.createDirectories(cacheDir);
            Files.writeString(cacheDir.resolve(".ccx-repo"), "org/myplugins\n");
            Files.writeString(cacheDir.resolve(".ccx-fetched"), Instant.now().toString());
            Files.writeString(cacheDir.resolve("plugin.md"), "cached content");

            var result = svc.ensureLocal(githubSource("myplugins", "org/myplugins"));

            assertThat(svc.cloneCalled).isFalse();
            assertThat(svc.pullCalled).isFalse();
            assertThat(cacheDir.resolve("plugin.md")).exists();
            assertThat(result).isEqualTo(cacheDir);
        }
    }

    @Nested
    @DisplayName("refresh()")
    class Refresh {

        @Test
        @DisplayName("a cache cloned from another repo is discarded rather than pulled")
        void refresh_cacheFromDifferentRepo_isDiscarded() throws IOException {
            var svc = new TestableService();
            var cacheDir = svc.localCacheDir("myplugins");
            Files.createDirectories(cacheDir);
            Files.writeString(cacheDir.resolve(".ccx-repo"), "attacker/evil\n");

            svc.refresh(githubSource("myplugins", "org/myplugins"));

            assertThat(svc.pullCalled)
                .as("pulling inside a foreign clone would keep the wrong repository's content")
                .isFalse();
            assertThat(svc.cloneCalled).isTrue();
            assertThat(svc.lastClonedRepo).isEqualTo("org/myplugins");
        }

        @Test
        @DisplayName("a matching cache is pulled and the provenance marker is preserved")
        void refresh_matchingProvenance_pulls() throws IOException {
            var svc = new TestableService();
            var cacheDir = svc.localCacheDir("myplugins");
            Files.createDirectories(cacheDir);
            Files.writeString(cacheDir.resolve(".ccx-repo"), "org/myplugins\n");

            svc.refresh(githubSource("myplugins", "org/myplugins"));

            assertThat(svc.pullCalled).isTrue();
            assertThat(svc.cloneCalled).isFalse();
            assertThat(cacheDir.resolve(".ccx-repo")).hasContent("org/myplugins");
        }

        @Test
        @DisplayName("rejects a directory source whose path is outside the allowed roots")
        void ensureLocalRejectsDirectorySourceOutsideAllowedRoots() {
            var svc = new TestableService();
            var source = dirSource("attack", "/etc");

            assertThatThrownBy(() -> svc.ensureLocal(source))
                .as("a directory source pointing at /etc (outside allowed roots) must be rejected")
                .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("accepts a directory source whose path is inside the allowed roots")
        void ensureLocalAcceptsDirectorySourceInsideAllowedRoots() throws IOException {
            var svc = new TestableService();
            var source = dirSource("local", tempDir.toString());

            var result = svc.ensureLocal(source);

            assertThat(result).isEqualTo(tempDir);
            assertThat(svc.cloneCalled).isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // adversarial githubRepo validation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("githubRepo validation")
    class GithubRepoValidation {

        /**
         * Subclass that exposes the real {@code clone} format-validation while never
         * touching the network: a valid-format repo would build a ProcessBuilder, so we
         * only ever feed adversarial repos that must be rejected by validation FIRST.
         */
        class ValidatingService extends MarketplaceCloneService {
            void validate(String githubRepo) throws IOException {
                // Real clone() validates format before building any git ProcessBuilder.
                clone(githubRepo, tempDir.resolve("never-created"));
            }
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "rejects adversarial githubRepo: {0}")
        @org.junit.jupiter.params.provider.ValueSource(strings = {
            "../attack/repo",
            "owner&&rm-rf/repo",
            "/leading/slash",
            "../repo",
            "repo/..",
            "./repo",
            "../../etc"
        })
        @DisplayName("adversarial githubRepo values must be rejected by format validation")
        void clone_rejectsAdversarialGithubRepo(String adversarial) {
            var svc = new ValidatingService();

            assertThatThrownBy(() -> svc.validate(adversarial))
                .as("adversarial repo '%s' must be rejected by format validation, not passed to git", adversarial)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("invalid githubRepo");
        }
    }

    // -----------------------------------------------------------------------
    // Plan 040 Item 25 — null-path and adversarial-key guards
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ensureLocal() — null path for directory source")
    class EnsureLocalNullPath {

        @Test
        @DisplayName("throws IOException with 'has no path' when directory source has null path")
        void ensureLocal_directorySourceNullPath_throwsIOException() {
            var svc = new TestableService();
            // directory sourceType with path=null
            var source = new MarketplaceSource("missing-path-source", null, "directory", null);

            assertThatThrownBy(() -> svc.ensureLocal(source))
                .as("directory source with null path must throw IOException containing 'has no path'")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("has no path");
        }
    }

    @Nested
    @DisplayName("localCacheDir() — adversarial key validation")
    class LocalCacheDirKeyValidation {

        @Test
        @DisplayName("rejects a traversal key (../../attack) with IllegalArgumentException")
        void localCacheDir_rejectsTraversal() {
            var svc = new MarketplaceCloneService();

            assertThatThrownBy(() -> svc.localCacheDir("../../attack"))
                .as("localCacheDir must reject traversal keys")
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a key starting with a slash (/abs) with IllegalArgumentException")
        void localCacheDir_rejectsAbsoluteSlash() {
            var svc = new MarketplaceCloneService();

            assertThatThrownBy(() -> svc.localCacheDir("/abs"))
                .as("localCacheDir must reject absolute slash keys")
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a key containing a backslash (a\\b) with IllegalArgumentException")
        void localCacheDir_rejectsBackslash() {
            var svc = new MarketplaceCloneService();

            assertThatThrownBy(() -> svc.localCacheDir("a\\b"))
                .as("localCacheDir must reject keys containing backslash")
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a key containing a NUL byte with IllegalArgumentException")
        void localCacheDir_nullByteKey_throws() {
            var svc = new MarketplaceCloneService();
            assertThatThrownBy(() -> svc.localCacheDir("ke\0y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid marketplace key");
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "rejects key that resolves to the cache root: [{0}]")
        @org.junit.jupiter.params.provider.ValueSource(strings = {"", " ", ".", "..", "a b", "key;rm", "café"})
        @DisplayName("rejects keys outside the allowlist, including those resolving to the cache root")
        void localCacheDir_rejectsNonAllowlistedKeys(String key) {
            var svc = new MarketplaceCloneService();

            assertThatThrownBy(() -> svc.localCacheDir(key))
                .as("key [%s] must be rejected: it either escapes or collapses onto the cache root", key)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid marketplace key");
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "accepts key: {0}")
        @org.junit.jupiter.params.provider.ValueSource(strings = {
            "myplugins", "java-stack", "ccx.marketplace", "a_b", "Team123"
        })
        @DisplayName("accepts ordinary marketplace keys, resolving them under the cache root")
        void localCacheDir_acceptsAllowlistedKeys(String key) {
            var svc = new MarketplaceCloneService();

            var dir = svc.localCacheDir(key);

            assertThat(dir.getParent())
                .isEqualTo(Path.of(System.getProperty("user.home"), ".cache", "ccx", "marketplaces"));
            assertThat(dir.getFileName()).isEqualTo(Path.of(key));
        }
    }

    @Nested
    @DisplayName("isFresh()")
    class IsFresh {

        @Test
        void isFresh_returnsFalse_whenTimestampAbsent() throws IOException {
            var svc = new TestableService();
            Files.createDirectories(svc.localCacheDir("mkt"));
            assertThat(svc.isFresh("mkt")).isFalse();
        }

        @Test
        void isFresh_returnsFalse_whenTimestampOlderThan168h() throws IOException {
            var svc = new TestableService();
            var dir = svc.localCacheDir("mkt");
            Files.createDirectories(dir);
            var oldStamp = Instant.now().minus(169, ChronoUnit.HOURS).toString();
            Files.writeString(dir.resolve(".ccx-fetched"), oldStamp);
            assertThat(svc.isFresh("mkt")).isFalse();
        }

        @Test
        void isFresh_returnsTrue_whenTimestampFresh() throws IOException {
            var svc = new TestableService();
            var dir = svc.localCacheDir("mkt");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(".ccx-fetched"), Instant.now().toString());
            assertThat(svc.isFresh("mkt")).isTrue();
        }

        // isFresh() must use 168-hour (7-day) window, not 24-hour
        @Test
        @DisplayName("isFresh returns true when timestamp is 25h old (within 168h window)")
        void isFresh_returnsTrue_whenTimestamp25hOld_within168hWindow() throws IOException {
            var svc = new TestableService();
            var dir = svc.localCacheDir("mkt");
            Files.createDirectories(dir);
            // 25 hours ago — was stale under the old 24h window, must be fresh under 168h window
            var stamp = Instant.now().minus(25, ChronoUnit.HOURS).toString();
            Files.writeString(dir.resolve(".ccx-fetched"), stamp);
            assertThat(svc.isFresh("mkt"))
                .as("25h-old timestamp must be fresh under the 168h (7-day) window")
                .isTrue();
        }

        @Test
        @DisplayName("isFresh returns false when timestamp is 169h old (beyond 168h window)")
        void isFresh_returnsFalse_whenTimestamp169hOld() throws IOException {
            var svc = new TestableService();
            var dir = svc.localCacheDir("mkt");
            Files.createDirectories(dir);
            // 169 hours ago — beyond the 168h window
            var stamp = Instant.now().minus(169, ChronoUnit.HOURS).toString();
            Files.writeString(dir.resolve(".ccx-fetched"), stamp);
            assertThat(svc.isFresh("mkt"))
                .as("169h-old timestamp must not be fresh under the 168h (7-day) window")
                .isFalse();
        }
    }
}
