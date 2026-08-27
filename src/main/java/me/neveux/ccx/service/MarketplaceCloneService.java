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
import java.util.List;
import java.util.Map;

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

    public MarketplaceCloneService() {}

    /**
     * Returns the local cache directory for the marketplace identified by {@code key}
     * ({@code ~/.cache/ccx/marketplaces/<key>}). The directory may not exist yet.
     *
     * @param key marketplace key; must not contain path separators or null bytes
     * @return the cache directory path
     * @throws IllegalArgumentException if {@code key} is null or contains disallowed characters
     */
    public Path localCacheDir(String key) {
        if (key == null || key.contains("/") || key.contains("\\") || key.contains("..") || key.indexOf('\0') >= 0) {
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
        writeTimestamp(source.key());
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
        if (Files.isDirectory(cacheDir)) {
            pull(cacheDir);
        } else {
            clone(source.githubRepo(), cacheDir);
        }
        writeTimestamp(source.key());
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

    private void writeTimestamp(String key) throws IOException {
        var stampFile = localCacheDir(key).resolve(".ccx-fetched");
        FileUtils.atomicWrite(stampFile, Instant.now().toString());
    }
}
