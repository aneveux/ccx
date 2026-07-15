package me.neveux.ccx.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Path;

/**
 * Resolves the ccx configuration directory and the paths beneath it that store
 * profiles, global CLAUDE.md fragments, and per-profile assets.
 *
 * <p>The base directory defaults to {@code $XDG_CONFIG_HOME/ccx} (falling back to
 * {@code ~/.config/ccx}). Tests may supply an override via the
 * {@link #CcxConfigService(Path)} constructor to redirect all path resolution to a
 * temporary directory.
 */
@ApplicationScoped
public class CcxConfigService {

    private final Path configDirOverride;

    public CcxConfigService() {
        this.configDirOverride = null;
    }

    /**
     * Constructs a service that uses {@code configDir} as the root instead of the
     * default XDG/home-relative path. Intended for use in tests.
     *
     * @param configDir the directory to use as the ccx config root
     */
    public CcxConfigService(Path configDir) {
        this.configDirOverride = configDir;
    }

    /**
     * Returns the ccx config root directory — {@code $XDG_CONFIG_HOME/ccx} when the
     * environment variable is set and non-blank, otherwise {@code ~/.config/ccx}. An
     * override supplied at construction time takes precedence over both.
     *
     * @return the ccx configuration root directory (may not exist yet)
     */
    public Path configDir() {
        if (configDirOverride != null) return configDirOverride;
        var xdg = System.getenv("XDG_CONFIG_HOME");
        var base = (xdg != null && !xdg.isBlank())
            ? Path.of(xdg)
            : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("ccx");
    }

    /**
     * Returns the profiles root directory ({@code <configDir>/profiles}).
     *
     * @return the profiles root directory (may not exist yet)
     */
    public Path profilesDir() {
        return configDir().resolve("profiles");
    }

    /**
     * Returns the directory for the named profile ({@code <profilesDir>/<name>}).
     *
     * @param name profile name, must match {@code [a-z0-9-]+}
     * @return the profile directory (may not exist yet)
     * @throws IllegalArgumentException if {@code name} is null or contains disallowed characters
     */
    public Path profileDir(String name) {
        if (name == null || !name.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Invalid profile name: " + name);
        return profilesDir().resolve(name);
    }

    /**
     * Returns the global CLAUDE.md fragments directory ({@code <configDir>/claudemd}).
     *
     * @return the global claudemd directory (may not exist yet)
     */
    public Path globalClaudeMdDir() {
        return configDir().resolve("claudemd");
    }

    /**
     * Returns the CLAUDE.md fragments directory for the named profile
     * ({@code <profileDir>/<name>/claudemd}).
     *
     * @param name profile name, must match {@code [a-z0-9-]+}
     * @return the profile claudemd directory (may not exist yet)
     */
    public Path profileClaudeMdDir(String name) {
        return profileDir(name).resolve("claudemd");
    }
}
