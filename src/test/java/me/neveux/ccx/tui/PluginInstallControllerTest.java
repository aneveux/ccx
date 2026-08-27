package me.neveux.ccx.tui;

import me.neveux.ccx.model.Settings;
import me.neveux.ccx.service.ClaudePluginInstallService;
import me.neveux.ccx.service.ClaudePluginRegistryService;
import me.neveux.ccx.service.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PluginInstallController")
class PluginInstallControllerTest {

    private static final long WAIT_MILLIS = 5_000;

    /** Reports a fixed set of plugins as missing an installation record for the project. */
    private static class StubRegistryService extends ClaudePluginRegistryService {
        private List<String> missing = List.of();

        @Override
        public boolean isInstalledFor(String qualifiedName, Path projectRoot) {
            return !missing.contains(qualifiedName);
        }

        @Override
        public List<String> missingInstalls(Map<String, Boolean> enabledPlugins, Path projectRoot) {
            return missing;
        }
    }

    /** Records install attempts instead of spawning the Claude Code CLI. */
    private static class RecordingInstallService extends ClaudePluginInstallService {
        private final List<String> installed = new CopyOnWriteArrayList<>();
        private final List<Path> workingDirs = new CopyOnWriteArrayList<>();
        private final List<Thread> installThreads = new CopyOnWriteArrayList<>();
        private InstallResult result = new InstallResult(Status.INSTALLED, "installed");

        @Override
        public InstallResult install(String qualifiedName, Path projectRoot) {
            installed.add(qualifiedName);
            workingDirs.add(projectRoot);
            installThreads.add(Thread.currentThread());
            return result;
        }
    }

    private Path projectDir;
    private SettingsService settingsService;
    private StubRegistryService registryService;
    private RecordingInstallService installService;
    private final List<String> statusMessages = new CopyOnWriteArrayList<>();
    /** Every controller a test built, so none of their install threads outlives the test. */
    private final List<PluginInstallController> controllers = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        projectDir = Files.createTempDirectory("ccx-plugin-install");
        settingsService = new SettingsService();
        registryService = new StubRegistryService();
        installService = new RecordingInstallService();
        statusMessages.clear();
        controllers.clear();
    }

    @AfterEach
    void tearDown() throws IOException {
        controllers.forEach(PluginInstallController::close);
        try (var paths = Files.walk(projectDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("could not clean up " + path, e);
                }
            });
        }
    }

    private PluginInstallController controller() {
        var controller = new PluginInstallController(settingsService, registryService, installService, projectDir,
            (message, seconds) -> statusMessages.add(message));
        controllers.add(controller);
        return controller;
    }

    @Test
    @DisplayName("installPlugin should install off the render thread and flag a snapshot refresh")
    void installPlugin_installsInBackground() {
        registryService.missing = List.of("slack@mkt");
        var controller = controller();

        controller.installPlugin("slack@mkt");
        controller.awaitQuiescence(WAIT_MILLIS);

        assertThat(installService.installed)
            .as("a toggled-on plugin with no install record must be installed")
            .containsExactly("slack@mkt");
        assertThat(installService.workingDirs)
            .as("the install must run against the project, not the process cwd")
            .containsExactly(projectDir);
        assertThat(statusMessages)
            .as("the footer must show the install starting and succeeding")
            .containsExactly(Glyphs.INFO + " installing slack@mkt…", Glyphs.OK + " installed slack@mkt");
        assertThat(controller.consumeRefreshPending())
            .as("a finished install must trigger exactly one snapshot rebuild")
            .isTrue();
        assertThat(controller.consumeRefreshPending())
            .as("the refresh flag must reset once consumed")
            .isFalse();
        assertThat(controller.installing())
            .as("nothing must remain in flight once the install thread is quiescent")
            .isFalse();
    }

    @Test
    @DisplayName("installPlugin should surface a failure in the footer without throwing")
    void installPlugin_surfacesFailure() {
        registryService.missing = List.of("slack@mkt");
        installService.result = new ClaudePluginInstallService.InstallResult(
            ClaudePluginInstallService.Status.CLI_NOT_FOUND, "no Claude Code CLI on PATH");
        var controller = controller();

        controller.installPlugin("slack@mkt");
        controller.awaitQuiescence(WAIT_MILLIS);

        assertThat(statusMessages)
            .as("a failed install must be visible in the footer with its reason")
            .contains(Glyphs.WARNING + " slack@mkt: no Claude Code CLI on PATH");
    }

    @Test
    @DisplayName("installPlugin should condense a multi-line failure to fit the one-row footer")
    void installPlugin_condensesLongFailure() {
        registryService.missing = List.of("slack@mkt");
        installService.result = new ClaudePluginInstallService.InstallResult(
            ClaudePluginInstallService.Status.FAILED,
            "error: marketplace 'mkt' could not be reached after three attempts, giving up now\n"
                + "stack trace line\nrun `claude plugin install slack@mkt --scope project`");
        var controller = controller();

        controller.installPlugin("slack@mkt");
        controller.awaitQuiescence(WAIT_MILLIS);

        assertThat(statusMessages)
            .filteredOn(message -> message.startsWith(Glyphs.WARNING))
            .as("the footer is one row: the reason must be a single truncated line")
            .singleElement()
            .satisfies(message -> {
                assertThat(message).doesNotContain("\n");
                assertThat(message).contains("slack@mkt: error: marketplace 'mkt' could not be reached");
                assertThat(message).endsWith("…");
            });
    }

    @Test
    @DisplayName("installPlugin should skip a plugin Claude Code already has installed")
    void installPlugin_skipsAlreadyInstalled() {
        var controller = controller();

        controller.installPlugin("slack@mkt");
        controller.awaitQuiescence(WAIT_MILLIS);

        assertThat(installService.installed)
            .as("an already-installed plugin must not be reinstalled")
            .isEmpty();
        assertThat(statusMessages)
            .as("a no-op must not clutter the footer with an install line")
            .noneMatch(message -> message.contains("installing"));
    }

    @Test
    @DisplayName("installEnabledPlugins should install every enabled plugin missing an install record")
    void installEnabledPlugins_installsGap() throws IOException {
        settingsService.write(
            new Settings().withEnabledPlugins(Map.of("slack@mkt", true, "jira@mkt", true)), projectDir);
        registryService.missing = List.of("slack@mkt", "jira@mkt");
        var controller = controller();

        controller.installEnabledPlugins();
        controller.awaitQuiescence(WAIT_MILLIS);

        assertThat(installService.installed)
            .as("enabling a profile must install each plugin it brought in, in declaration order")
            .containsExactly("slack@mkt", "jira@mkt");
    }

    @Test
    @DisplayName("installEnabledPlugins should do nothing when no plugin is missing")
    void installEnabledPlugins_noopWhenComplete() throws IOException {
        settingsService.write(new Settings().withEnabledPlugins(Map.of("slack@mkt", true)), projectDir);
        var controller = controller();

        controller.installEnabledPlugins();
        controller.awaitQuiescence(WAIT_MILLIS);

        assertThat(installService.installed)
            .as("a fully installed project needs no install")
            .isEmpty();
        assertThat(controller.consumeRefreshPending())
            .as("no install means no reason to rebuild the snapshot")
            .isFalse();
    }

    @Test
    @DisplayName("close should end the install thread instead of parking it for the JVM's lifetime")
    void close_terminatesTheInstallThread() throws InterruptedException {
        registryService.missing = List.of("slack@mkt");
        var controller = controller();

        controller.installPlugin("slack@mkt");
        controller.awaitQuiescence(WAIT_MILLIS);
        var installThread = installService.installThreads.getFirst();

        assertThat(installThread.isAlive())
            .as("the single install thread stays parked between installs while the controller is open")
            .isTrue();

        controller.close();
        installThread.join(WAIT_MILLIS);

        assertThat(installThread.isAlive())
            .as("a closed controller must release its install thread — one leaked per TUI session before")
            .isFalse();
    }

    @Test
    @DisplayName("a controller reused after close installs again on a fresh thread")
    void installPlugin_afterClose_stillInstalls() {
        registryService.missing = List.of("slack@mkt", "jira@mkt");
        var controller = controller();

        controller.installPlugin("slack@mkt");
        controller.awaitQuiescence(WAIT_MILLIS);
        controller.close();

        controller.installPlugin("jira@mkt");
        controller.awaitQuiescence(WAIT_MILLIS);

        assertThat(installService.installed)
            .as("close must not reject later work — the executor is lazily recreated")
            .containsExactly("slack@mkt", "jira@mkt");
    }

    @Test
    @DisplayName("installPlugin should ignore a blank plugin name without touching the install thread")
    void installPlugin_ignoresBlankName() {
        var controller = controller();

        controller.installPlugin("  ");

        assertThat(installService.installed)
            .as("a blank name must never reach the install service")
            .isEmpty();
        assertThat(controller.installing())
            .as("a rejected name must not register as in-flight work")
            .isFalse();
    }
}
