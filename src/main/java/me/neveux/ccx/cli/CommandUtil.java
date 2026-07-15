package me.neveux.ccx.cli;

import me.neveux.ccx.service.PathSecurity;
import picocli.CommandLine;

import java.nio.file.Path;

/**
 * Shared utilities for ccx picocli commands: directory resolution, path-security
 * checks, and the shared name-validation pattern. All methods are static; this
 * class is not instantiable.
 */
final class CommandUtil {

    private CommandUtil() {}

    public static final String VALID_NAME_PATTERN = "[a-z0-9-]+";

    /**
     * Returns {@code globalDir} if non-null, otherwise defaults to {@code ~/.claude}.
     */
    static Path resolveGlobalDir(Path globalDir) {
        return globalDir != null ? globalDir
            : Path.of(System.getProperty("user.home")).resolve(".claude");
    }

    /**
     * Returns {@code projectDir} if non-null, otherwise defaults to {@code Path.of(".")}.
     */
    static Path resolveProjectDir(Path projectDir) {
        return projectDir != null ? projectDir : Path.of(".");
    }

    /**
     * Checks that {@code dir} is within allowed directories.
     * Prints an error and returns {@code false} if not. The check is always
     * performed — a null {@code spec} (command instantiated outside picocli, e.g.
     * before {@code @Spec} injection) must not silently skip security; the error
     * falls back to {@code System.err} in that case.
     */
    static boolean checkPathSecurity(Path dir, CommandLine.Model.CommandSpec spec) {
        return checkPathSecurity(dir, spec, "--project-dir");
    }

    /**
     * Variant of {@link #checkPathSecurity(Path, CommandLine.Model.CommandSpec)} that names
     * the offending option in the error message (e.g. {@code --global-dir}).
     */
    static boolean checkPathSecurity(Path dir, CommandLine.Model.CommandSpec spec, String optionLabel) {
        if (!PathSecurity.isAllowed(dir)) {
            var err = spec != null ? spec.commandLine().getErr()
                : new java.io.PrintWriter(System.err, true);
            err.println("Error: " + optionLabel + " is outside allowed directories: " + dir);
            return false;
        }
        return true;
    }
}
