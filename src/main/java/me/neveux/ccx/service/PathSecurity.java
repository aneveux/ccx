package me.neveux.ccx.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Guards against path-traversal attacks by checking that a candidate path stays within
 * allowed roots (user home, temp dir, current working directory).
 *
 * <p>Use {@link #isAllowed} for paths that may not yet exist on disk — it falls back to
 * {@code normalize()} when {@code toRealPath()} fails, so it accepts brand-new project dirs.
 * Use {@link #resolveAndCheck} when the path must already exist and symlink traversal must
 * be strictly blocked — it returns {@code false} for non-existent paths.
 */
public final class PathSecurity {

    private PathSecurity() {}

    private static List<Path> allowedRoots() {
        return List.of(
            Path.of(System.getProperty("user.home")).toAbsolutePath().normalize(),
            Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize(),
            Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        );
    }

    private static boolean withinAllowed(Path resolved) {
        return allowedRoots().stream().anyMatch(resolved::startsWith);
    }

    /**
     * Returns {@code true} when {@code candidate} resolves to a path within one of the
     * allowed roots (user home, temp dir, current working directory).
     *
     * <p>If the path does not yet exist on disk, lexical normalization via
     * {@link Path#toAbsolutePath()} is used as a fallback, accepting write targets for
     * new projects. A symlink created after this check could theoretically escape bounds,
     * but this is acceptable for a single-user CLI.
     *
     * @param candidate the path to test; may be relative or refer to a not-yet-existing file
     * @return {@code false} if {@code candidate} is null or resolves outside allowed roots
     */
    public static boolean isAllowed(Path candidate) {
        if (candidate == null) return false;
        Path resolved;
        try {
            resolved = candidate.toRealPath();
        } catch (IOException e) {
            // Path does not exist yet (write target for new-project bootstrap); use lexical
            // normalization. A symlink created after this check but before the write could
            // theoretically escape bounds — acceptable for a single-user CLI.
            resolved = candidate.toAbsolutePath().normalize();
        }
        return withinAllowed(resolved);
    }

    /**
     * Resolves all symlinks via {@link Path#toRealPath()} and checks that the canonical
     * path is within one of the allowed roots. Returns {@code false} if the path does not
     * exist (since symlink traversal cannot be verified for non-existent paths).
     *
     * <p>Use this overload for paths that must already exist and where symlink traversal
     * must be strictly blocked — for example, marketplace clone directories.
     *
     * @param candidate the path to test; must exist on disk
     * @return {@code false} if {@code candidate} is null, does not exist, or resolves outside allowed roots
     */
    public static boolean resolveAndCheck(Path candidate) {
        if (candidate == null) return false;
        try {
            return withinAllowed(candidate.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }
}
