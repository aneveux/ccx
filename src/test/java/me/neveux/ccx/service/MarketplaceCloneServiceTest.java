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
            Files.writeString(cacheDir.resolve(".ccx-fetched"),
                Instant.now().minus(169, ChronoUnit.HOURS).toString());

            var result = svc.ensureLocal(source);

            assertThat(svc.pullCalled).isTrue();
            assertThat(svc.cloneCalled).isFalse();
            assertThat(result).isEqualTo(cacheDir);
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
