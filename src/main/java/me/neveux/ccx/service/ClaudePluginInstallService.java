package me.neveux.ccx.service;

import jakarta.enterprise.context.ApplicationScoped;
import me.neveux.ccx.util.ProcessRunner;
import me.neveux.ccx.util.ProcessRunner.ProcessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Installs a plugin for a project by delegating to the Claude Code CLI
 * ({@code claude plugin install <plugin>@<marketplace> --scope project}).
 *
 * <p>Installation is delegated rather than reimplemented: version resolution, cache
 * materialization, the {@code .in_use} refcount and the {@code .orphaned_at} garbage-collection
 * marker are Claude Code internals that would silently rot if ccx wrote
 * {@code installed_plugins.json} itself.
 *
 * <p>Scope is always {@code project}: a {@code user}-scope install would also add the plugin to
 * {@code ~/.claude/settings.json}, and ccx deliberately refuses to manage plugins enabled
 * globally — user scope would make every ccx-installed plugin untoggleable.
 *
 * <p>Never throws at its call sites: every failure mode is returned as an {@link InstallResult}
 * so callers can still write the enablement and surface actionable advice.
 */
@ApplicationScoped
public class ClaudePluginInstallService {

    private static final Logger log = LoggerFactory.getLogger(ClaudePluginInstallService.class);

    /** Environment variable that pins the Claude Code CLI, bypassing discovery. */
    public static final String CLI_ENV_OVERRIDE = "CCX_CLAUDE_BIN";

    static final int INSTALL_TIMEOUT_SECONDS = 180;

    private static final int DISCOVERY_TIMEOUT_SECONDS = 10;

    /**
     * Accepted plugin identifiers. Deliberately narrow: the value ends up as a subprocess
     * argument, so anything outside this alphabet is rejected before a process is spawned. Both
     * halves must start alphanumerically so a name can never be mistaken for a CLI flag.
     */
    private static final Pattern QUALIFIED_NAME =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*@[A-Za-z0-9][A-Za-z0-9._-]*");

    /** Fallback install locations, checked in order when {@code claude} is not on PATH. */
    private static final List<List<String>> FALLBACK_CLI_PATHS = List.of(
        List.of(".local", "bin", "claude"),
        List.of(".claude", "local", "claude"));

    private volatile Optional<Path> resolvedCli;

    /** Why an install attempt ended the way it did. */
    public enum Status {
        /** The Claude Code CLI reported success, or the plugin was already installed. */
        INSTALLED,
        /** No Claude Code CLI could be located, so nothing was attempted. */
        CLI_NOT_FOUND,
        /** The plugin identifier is not a valid {@code <plugin>@<marketplace>} pair. */
        INVALID_NAME,
        /** The CLI ran but did not install the plugin. */
        FAILED
    }

    /**
     * Outcome of an install attempt.
     *
     * @param status  how the attempt ended
     * @param message human-readable detail, including the manual remediation command on failure
     */
    public record InstallResult(Status status, String message) {

        /**
         * @return true when the plugin is now installed for the project
         */
        public boolean isSuccess() {
            return status == Status.INSTALLED;
        }
    }

    public ClaudePluginInstallService() {}

    /**
     * Installs {@code qualifiedName} for {@code projectRoot} with project scope.
     *
     * <p>Idempotent: the Claude Code CLI exits successfully when the plugin is already installed
     * for that scope.
     *
     * @param qualifiedName plugin identifier in {@code <plugin>@<marketplace>} form
     * @param projectRoot   project the plugin is being enabled in; becomes the CLI working
     *                      directory so Claude Code resolves the project's marketplaces
     * @return the outcome; never null, never thrown
     */
    public InstallResult install(String qualifiedName, Path projectRoot) {
        if (qualifiedName == null || !QUALIFIED_NAME.matcher(qualifiedName).matches()) {
            log.debug("refusing to install a malformed plugin identifier name={}", qualifiedName);
            return new InstallResult(Status.INVALID_NAME,
                "not a valid <plugin>@<marketplace> identifier: " + qualifiedName);
        }

        var cli = claudeCli();
        if (cli.isEmpty()) {
            log.warn("Claude Code CLI not found, cannot install plugin name={}", qualifiedName);
            return new InstallResult(Status.CLI_NOT_FOUND,
                "Claude Code CLI not found (set " + CLI_ENV_OVERRIDE + " or put `claude` on PATH) — run `"
                    + remediationCommand(qualifiedName) + "` in the project");
        }

        var args = List.of(cli.get().toString(), "plugin", "install", qualifiedName, "--scope", "project");
        try {
            var result = runInstall(args, projectRoot);
            if (result.isSuccess()) {
                log.info("installed plugin name={} project={}", qualifiedName, projectRoot);
                return new InstallResult(Status.INSTALLED, result.output().strip());
            }
            log.warn("plugin install failed name={} exit={} output={}",
                qualifiedName, result.exitCode(), result.output().strip());
            return new InstallResult(Status.FAILED, failureMessage(qualifiedName, result.output().strip()));
        } catch (IOException e) {
            log.warn("plugin install could not be run name={} project={}", qualifiedName, projectRoot, e);
            return new InstallResult(Status.FAILED, failureMessage(qualifiedName, e.getMessage()));
        }
    }

    /**
     * Installs each of {@code qualifiedNames} for {@code projectRoot}, in order, and returns the
     * outcome of every attempt keyed by plugin identifier.
     *
     * <p>One failure does not stop the others: a marketplace that cannot resolve one plugin says
     * nothing about the rest.
     *
     * @param qualifiedNames plugin identifiers to install; may be null or empty
     * @param projectRoot    project the plugins are being enabled in
     * @return outcomes in attempt order; empty when there was nothing to install
     */
    public Map<String, InstallResult> installAll(List<String> qualifiedNames, Path projectRoot) {
        return installAll(qualifiedNames, projectRoot, name -> {});
    }

    /**
     * Same as {@link #installAll(List, Path)}, notifying {@code beforeEach} with the plugin
     * identifier just before each attempt starts. Every install is a CLI subprocess that can take
     * seconds, so a caller rendering progress needs the notification before the call, not after.
     *
     * @param qualifiedNames plugin identifiers to install; may be null or empty
     * @param projectRoot    project the plugins are being enabled in
     * @param beforeEach     called with each identifier before its install is attempted
     * @return outcomes in attempt order; empty when there was nothing to install
     */
    public Map<String, InstallResult> installAll(List<String> qualifiedNames, Path projectRoot,
                                                 Consumer<String> beforeEach) {
        if (qualifiedNames == null || qualifiedNames.isEmpty()) return Map.of();
        var results = new LinkedHashMap<String, InstallResult>();
        for (var qualifiedName : qualifiedNames) {
            beforeEach.accept(qualifiedName);
            results.put(qualifiedName, install(qualifiedName, projectRoot));
        }
        return results;
    }

    /**
     * Returns the command a user can run by hand to install {@code qualifiedName}, kept identical
     * to the one Claude Code suggests in its own {@code plugin-not-installed} error.
     *
     * @param qualifiedName plugin identifier in {@code <plugin>@<marketplace>} form
     * @return a copy-pasteable shell command
     */
    public String remediationCommand(String qualifiedName) {
        return "claude plugin install " + qualifiedName + " --scope project";
    }

    /**
     * Locates the Claude Code CLI, memoized for the lifetime of this service.
     *
     * <p>Discovery order: the {@value #CLI_ENV_OVERRIDE} environment variable, then {@code claude}
     * on {@code PATH}, then the two conventional install locations under the home directory. An
     * explicit override that does not resolve is not backfilled from PATH — a pinned binary that
     * is wrong must fail visibly.
     *
     * @return the CLI path, or empty when none was found
     */
    protected Optional<Path> claudeCli() {
        var memoized = resolvedCli;
        if (memoized != null) return memoized;
        synchronized (this) {
            if (resolvedCli == null) {
                resolvedCli = discoverCli();
            }
            return resolvedCli;
        }
    }

    /**
     * Runs the install subprocess. Overridable so tests can assert on the argv without spawning
     * a real Claude Code CLI.
     *
     * @param args       full argv, executable first
     * @param workingDir directory to run in
     * @return the exit status and combined output
     * @throws IOException if the process cannot be started, times out, or is interrupted
     */
    protected ProcessResult runInstall(List<String> args, Path workingDir) throws IOException {
        return ProcessRunner.run(args, workingDir, null, INSTALL_TIMEOUT_SECONDS);
    }

    private String failureMessage(String qualifiedName, String detail) {
        var reason = detail == null || detail.isBlank() ? "no output" : detail;
        return reason + " — run `" + remediationCommand(qualifiedName) + "` in the project to see the full error";
    }

    /**
     * Returns the raw {@value #CLI_ENV_OVERRIDE} value. Overridable so tests can drive the
     * discovery ladder without mutating the process environment.
     *
     * @return the pinned CLI location, or null when unset
     */
    protected String cliEnvOverride() {
        return System.getenv(CLI_ENV_OVERRIDE);
    }

    /**
     * Returns the home directory the fallback locations are resolved against. Overridable so tests
     * can point it at a temp directory.
     *
     * @return the home directory path, or null when the JVM reports none
     */
    protected String homeDirectory() {
        return System.getProperty("user.home");
    }

    /**
     * Runs the {@code which claude} lookup. Overridable so tests can exercise the ladder without
     * depending on whether the machine running them has the CLI installed.
     *
     * @param args full argv, executable first
     * @return the exit status and combined output
     * @throws IOException if the process cannot be started, times out, or is interrupted
     */
    protected ProcessResult runDiscovery(List<String> args) throws IOException {
        return ProcessRunner.run(args, null, null, DISCOVERY_TIMEOUT_SECONDS);
    }

    private Optional<Path> discoverCli() {
        var override = cliEnvOverride();
        if (override != null && !override.isBlank()) {
            return executableAt(override);
        }
        var onPath = cliOnPath();
        if (onPath.isPresent()) return onPath;

        var home = homeDirectory();
        if (home == null) return Optional.empty();
        return FALLBACK_CLI_PATHS.stream()
            .map(segments -> Path.of(home, segments.toArray(String[]::new)))
            .filter(Files::isRegularFile)
            .findFirst();
    }

    private Optional<Path> cliOnPath() {
        try {
            var result = runDiscovery(List.of("which", "claude"));
            if (!result.isSuccess()) return Optional.empty();
            return result.output().lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .findFirst()
                .flatMap(this::executableAt);
        } catch (IOException e) {
            log.debug("could not look up claude on PATH", e);
            return Optional.empty();
        }
    }

    private Optional<Path> executableAt(String location) {
        try {
            var path = Path.of(location);
            if (!Files.isRegularFile(path)) {
                log.debug("claude CLI candidate is not a file path={}", path);
                return Optional.empty();
            }
            return Optional.of(path);
        } catch (InvalidPathException e) {
            log.debug("claude CLI candidate is not a usable path location={}", location, e);
            return Optional.empty();
        }
    }
}
