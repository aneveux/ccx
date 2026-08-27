package me.neveux.ccx.tui;

import me.neveux.ccx.service.ClaudePluginInstallService;
import me.neveux.ccx.service.ClaudePluginRegistryService;
import me.neveux.ccx.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Owns the TUI's background plugin installation: enabling a plugin in {@code settings.json} is
 * not enough for Claude Code to load it, so the matching {@code claude plugin install} must run
 * too — and that spawns a subprocess which may clone a marketplace over the network.
 *
 * <p>The render thread must never block on that, so every install is dispatched to a background
 * daemon thread and its progress is published to the shared footer status line. Installs are
 * serialized on a single thread because concurrent {@code claude plugin install} invocations
 * would race on Claude Code's own {@code installed_plugins.json}.
 *
 * <p>State crosses the install thread / render thread boundary, so every mutable field is
 * {@code volatile}.
 */
final class PluginInstallController {

    private static final Logger log = LoggerFactory.getLogger(PluginInstallController.class);

    private static final long STATUS_SECONDS = 5;

    /** Characters of an install failure the footer can show before the line is clipped. */
    private static final int FOOTER_MESSAGE_LIMIT = 60;

    private final SettingsService settingsService;
    private final ClaudePluginRegistryService claudePluginRegistryService;
    private final ClaudePluginInstallService claudePluginInstallService;
    private final Path projectDir;
    private final BiConsumer<String, Long> statusSink;

    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean refreshPending = false;

    private ExecutorService executor;

    PluginInstallController(SettingsService settingsService,
                            ClaudePluginRegistryService claudePluginRegistryService,
                            ClaudePluginInstallService claudePluginInstallService,
                            Path projectDir,
                            BiConsumer<String, Long> statusSink) {
        this.settingsService = settingsService;
        this.claudePluginRegistryService = claudePluginRegistryService;
        this.claudePluginInstallService = claudePluginInstallService;
        this.projectDir = projectDir;
        this.statusSink = statusSink;
    }

    /**
     * Installs {@code qualifiedName} for the project unless Claude Code already has a record for
     * it. Returns immediately; the work happens on the install thread.
     *
     * @param qualifiedName plugin identifier in {@code <plugin>@<marketplace>} form
     */
    void installPlugin(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) return;
        submit(() -> installAll(List.of(qualifiedName)));
    }

    /**
     * Installs every plugin the project has enabled but Claude Code cannot load, which is the
     * state a freshly-enabled profile leaves behind. Returns immediately.
     */
    void installEnabledPlugins() {
        submit(() -> {
            var settings = settingsService.read(projectDir);
            installAll(claudePluginRegistryService.missingInstalls(settings.enabledPlugins(), projectDir));
        });
    }

    /** True while an install is in flight — keeps the event loop ticking until it finishes. */
    boolean installing() {
        return inFlight.get() > 0;
    }

    /**
     * Returns {@code true} once after an install finishes, then resets. Drives the snapshot
     * rebuild so the row reflects the new state.
     */
    boolean consumeRefreshPending() {
        if (!refreshPending) return false;
        refreshPending = false;
        return true;
    }

    /**
     * Awaits the completion of everything submitted so far by queueing behind it on the same
     * single thread. Test seam — the TUI itself never blocks on installs.
     *
     * @param millis how long to wait before giving up
     * @throws IllegalStateException if the wait times out or is interrupted
     */
    void awaitQuiescence(long millis) {
        try {
            executor().submit(() -> {}).get(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for plugin installs", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("plugin installs did not finish within " + millis + "ms", e);
        }
    }

    private void installAll(List<String> qualifiedNames) {
        if (qualifiedNames.isEmpty()) {
            log.debug("no plugin install needed projectDir={}", projectDir);
            return;
        }
        for (var qualifiedName : qualifiedNames) {
            if (claudePluginRegistryService.isInstalledFor(qualifiedName, projectDir)) continue;
            statusSink.accept(Glyphs.INFO + " installing " + qualifiedName + "…", STATUS_SECONDS);
            var result = claudePluginInstallService.install(qualifiedName, projectDir);
            if (result.isSuccess()) {
                statusSink.accept(Glyphs.OK + " installed " + qualifiedName, STATUS_SECONDS);
            } else {
                statusSink.accept(Glyphs.WARNING + " " + qualifiedName + ": " + footerSafe(result.message()),
                    STATUS_SECONDS);
            }
        }
        refreshPending = true;
    }

    /**
     * Condenses an install failure into something a one-row footer can show: the footer is a
     * single {@code Constraint.length(1)} line, so a raw multi-line subprocess dump would be
     * clipped and hide the part that matters.
     */
    private static String footerSafe(String message) {
        if (message == null || message.isBlank()) return "install failed";
        var firstLine = message.lines().findFirst().orElse("").strip();
        if (firstLine.isEmpty()) return "install failed";
        return firstLine.length() <= FOOTER_MESSAGE_LIMIT
            ? firstLine
            : firstLine.substring(0, FOOTER_MESSAGE_LIMIT - 1) + "…";
    }

    private void submit(Runnable task) {
        inFlight.incrementAndGet();
        executor().submit(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("background plugin install failed projectDir={}", projectDir, e);
                statusSink.accept(Glyphs.WARNING + " plugin install failed: " + e.getMessage(), STATUS_SECONDS);
            } finally {
                inFlight.decrementAndGet();
            }
        });
    }

    private synchronized ExecutorService executor() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(runnable -> {
                var thread = new Thread(runnable, "ccx-plugin-install");
                thread.setDaemon(true);
                return thread;
            });
        }
        return executor;
    }
}
