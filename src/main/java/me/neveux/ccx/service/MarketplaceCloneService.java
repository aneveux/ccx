package me.neveux.ccx.service;

import me.neveux.ccx.model.MarketplaceSource;
import me.neveux.ccx.util.FileUtils;
import me.neveux.ccx.util.ProcessRunner;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Clones and updates marketplace git repositories into the local cache directory
 * ({@code ~/.cache/ccx/marketplaces/<key>}) and validates that directory-based
 * marketplace sources are within allowed filesystem roots.
 *
 * <p>GitHub sources are cloned with {@code --depth=1} on first access and pulled
 * with {@code --ff-only} on subsequent refreshes. A timestamp file ({@code .ccx-fetched})
 * records when the last successful clone/pull occurred; sources are considered fresh for
 * {@link #CACHE_TTL} (7 days) after that timestamp.
 *
 * <p>Git operations run in a subprocess with a {@value #GIT_TIMEOUT_SECONDS}-second
 * timeout; stdout is drained on a virtual thread to prevent pipe-buffer deadlocks.
 */
@ApplicationScoped
public class MarketplaceCloneService {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceCloneService.class);

    /** How long a fetched marketplace clone stays fresh before a refresh is due. */
    public static final Duration CACHE_TTL = Duration.ofDays(7);

    /** File inside a cache directory recording which GitHub repository the clone came from. */
    private static final String REPO_MARKER = ".ccx-repo";

    /** Characters a marketplace key may consist of; anything else could escape the cache root. */
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9._-]+");

    public MarketplaceCloneService() {}

    /**
     * Returns the local cache directory for the marketplace identified by {@code key}
     * ({@code ~/.cache/ccx/marketplaces/<key>}). The directory may not exist yet.
     *
     * <p>Keys are allowlisted to {@code [A-Za-z0-9._-]+} rather than merely screened for
     * separators: an empty key or a bare {@code .} would otherwise resolve to the shared cache
     * root itself, so a refresh would treat every cached marketplace as one clone.
     *
     * @param key marketplace key; must be non-empty, match {@code [A-Za-z0-9._-]+}, and be
     *            neither {@code .} nor {@code ..}
     * @return the cache directory path
     * @throws IllegalArgumentException if {@code key} is null or contains disallowed characters
     */
    public Path localCacheDir(String key) {
        if (key == null || !VALID_KEY.matcher(key).matches() || key.equals(".") || key.equals("..")) {
            throw new IllegalArgumentException("invalid marketplace key: " + key);
        }
        return Path.of(System.getProperty("user.home"), ".cache", "ccx", "marketplaces", key);
    }

    /**
     * Returns {@code true} if the cached clone for {@code key} has a valid timestamp that
     * is within {@link #CACHE_TTL} of the current time.
     *
     * @param key marketplace key
     * @return {@code true} when the cache is fresh and does not need a network pull
     */
    public boolean isFresh(String key) {
        var stampFile = localCacheDir(key).resolve(".ccx-fetched");
        if (!Files.exists(stampFile)) return false;
        try {
            var stamp = Instant.parse(Files.readString(stampFile).strip());
            return stamp.isAfter(Instant.now().minus(CACHE_TTL));
        } catch (Exception e) {
            log.warn("could not parse marketplace freshness stamp key={}", key, e);
            return false;
        }
    }

    /**
     * Ensures that the marketplace described by {@code source} is available locally and
     * returns the path to its root directory.
     *
     * <ul>
     *   <li>For {@code directory} sources, validates the path with {@link PathSecurity} and
     *       returns it directly — no network access.</li>
     *   <li>For {@code github} sources, returns the cached clone if it is still fresh;
     *       otherwise pulls or clones from GitHub and writes a new timestamp.</li>
     * </ul>
     *
     * @param source the marketplace source descriptor
     * @return the local directory containing the marketplace contents
     * @throws IOException if a git operation fails, the path is unsafe, or the source is misconfigured
     */
    public Path ensureLocal(MarketplaceSource source) throws IOException {
        if (!"github".equals(source.sourceType())) {
            if (source.path() == null) {
                throw new IOException("directory marketplace source '" + source.key() + "' has no path");
            }
            if (!PathSecurity.resolveAndCheck(Path.of(source.path()))) {
                throw new IOException("directory marketplace source '" + source.key()
                    + "' path is outside allowed directories: " + source.path());
            }
            return Path.of(source.path());
        }
        var cacheDir = localCacheDir(source.key());
        discardForeignClone(cacheDir, source.key(), source.githubRepo());
        if (Files.isDirectory(cacheDir) && isFresh(source.key())) {
            log.debug("marketplace cache fresh key={}", source.key());
            return cacheDir;
        }
        if (Files.isDirectory(cacheDir)) {
            log.debug("marketplace cache stale, pulling key={} repo={}", source.key(), source.githubRepo());
            pull(cacheDir);
        } else {
            log.debug("marketplace cache absent, cloning key={} repo={}", source.key(), source.githubRepo());
            clone(source.githubRepo(), cacheDir);
        }
        recordFetch(source.key(), source.githubRepo());
        return cacheDir;
    }

    /**
     * Unconditionally refreshes the local clone for {@code source}, regardless of the
     * current freshness timestamp. Non-github sources are silently ignored.
     *
     * @param source the marketplace source to refresh
     * @throws IOException if the git pull or clone fails
     */
    public void refresh(MarketplaceSource source) throws IOException {
        if (!"github".equals(source.sourceType())) return;
        var cacheDir = localCacheDir(source.key());
        discardForeignClone(cacheDir, source.key(), source.githubRepo());
        if (Files.isDirectory(cacheDir)) {
            pull(cacheDir);
        } else {
            clone(source.githubRepo(), cacheDir);
        }
        recordFetch(source.key(), source.githubRepo());
    }

    /**
     * Deletes {@code cacheDir} unless it is a clone of {@code githubRepo}, so that a mismatch
     * results in a fresh clone rather than a reuse.
     *
     * <p>The cache is shared by every project on the machine while the key that addresses it comes
     * from whichever {@code settings.json} declared the marketplace. Without this check a project
     * could point a well-known key at its own repository and have every other project on the
     * machine resolve plugins from it. A clone with no recorded provenance (written by a ccx
     * predating the marker, or interrupted mid-clone) is discarded for the same reason.
     */
    private void discardForeignClone(Path cacheDir, String key, String githubRepo) throws IOException {
        if (!Files.isDirectory(cacheDir)) return;
        var recorded = recordedRepo(cacheDir);
        if (githubRepo != null && githubRepo.equals(recorded)) return;
        log.warn("marketplace cache key={} holds a clone of {} but {} is declared — discarding it",
            key, recorded != null ? recorded : "an unrecorded repository", githubRepo);
        deleteRecursively(cacheDir);
    }

    /** The repository a cached clone was created from, or {@code null} when it is not recorded. */
    private static String recordedRepo(Path cacheDir) {
        var marker = cacheDir.resolve(REPO_MARKER);
        if (!Files.isRegularFile(marker)) return null;
        try {
            return Files.readString(marker).strip();
        } catch (IOException e) {
            log.warn("could not read marketplace repo marker path={}", marker, e);
            return null;
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    static final int GIT_TIMEOUT_SECONDS = 120;

    private String runGitCommand(List<String> args) throws IOException {
        var result = ProcessRunner.run(args, null, Map.of("GIT_TERMINAL_PROMPT", "0"), GIT_TIMEOUT_SECONDS);
        if (!result.isSuccess()) {
            throw new IOException("git command failed (exit=" + result.exitCode() + "): " + result.output());
        }
        return result.output();
    }

    protected void clone(String githubRepo, Path targetDir) throws IOException {
        if (githubRepo == null || !githubRepo.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")) {
            throw new IOException("invalid githubRepo value: " + githubRepo);
        }
        for (var segment : githubRepo.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IOException("invalid githubRepo value: " + githubRepo);
            }
        }
        Files.createDirectories(targetDir.getParent());
        runGitCommand(List.of("git", "clone", "--depth=1",
            "https://github.com/" + githubRepo, targetDir.toString()));
    }

    protected void pull(Path dir) throws IOException {
        runGitCommand(List.of("git", "-C", dir.toString(), "pull", "--ff-only"));
    }

    /**
     * Records a successful fetch: the timestamp {@link #isFresh} reads, plus the repository the
     * clone came from so a later call can tell the cache entry apart from a different repository
     * claiming the same key.
     */
    private void recordFetch(String key, String githubRepo) throws IOException {
        var cacheDir = localCacheDir(key);
        FileUtils.atomicWrite(cacheDir.resolve(".ccx-fetched"), Instant.now().toString());
        if (githubRepo != null) {
            FileUtils.atomicWrite(cacheDir.resolve(REPO_MARKER), githubRepo + "\n");
        }
    }
}
