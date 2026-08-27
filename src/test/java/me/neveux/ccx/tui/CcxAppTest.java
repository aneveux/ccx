package me.neveux.ccx.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import me.neveux.ccx.model.ProfileDriftState;
import me.neveux.ccx.model.Settings;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.ClaudePluginInstallService;
import me.neveux.ccx.service.ClaudePluginRegistryService;
import me.neveux.ccx.service.ConfigReadException;
import me.neveux.ccx.service.HomeIsolatedServices;
import me.neveux.ccx.service.MarketplaceCloneService;
import me.neveux.ccx.service.ProfileLifecycleService;
import me.neveux.ccx.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the event loop with synthetic key events to pin the two install gates: enabling a plugin
 * or (re)installing a profile must trigger a background install, while disabling one — or a
 * mutation that failed — must not. An install spawns {@code claude plugin install}, so a gate
 * inverted by a later refactor would either silently skip installs or run subprocesses the user
 * never asked for.
 */
@DisplayName("CcxApp")
class CcxAppTest {

    private static final String PLUGIN = "fixture@fixture-mkt";
    private static final String PROFILE = "fixture-profile";

    @TempDir
    Path projectDir;

    private CannedDataService dataService;
    private InMemorySettingsService settingsService;
    private StubLifecycleService lifecycleService;
    private StubFetchController fetch;
    private RecordingInstallController pluginInstall;
    private CcxApp app;

    /** Returns whatever snapshot the test last installed, and counts the rebuilds it was asked for. */
    private static final class CannedDataService extends TuiDataService {
        private TuiSnapshot snapshot;
        private int snapshotCalls;

        CannedDataService(TuiSnapshot initial) {
            this.snapshot = initial;
        }

        @Override
        public TuiSnapshot snapshot(Path projectDir) {
            snapshotCalls++;
            return snapshot;
        }
    }

    /** Keeps settings in memory so a toggle never touches disk, and can fail the read on demand. */
    private static final class InMemorySettingsService extends SettingsService {
        private Settings projectSettings = new Settings();
        private ConfigReadException readFailure;

        @Override
        public Settings read(Path projectRoot) {
            // The global read is what TuiActions consults to refuse a globally-enabled plugin;
            // empty settings keep the developer's real ~/.claude out of the verdict.
            return new Settings();
        }

        @Override
        public Settings readForUpdate(Path projectRoot) {
            if (readFailure != null) throw readFailure;
            return projectSettings;
        }

        @Override
        public void write(Settings settings, Path projectRoot) {
            this.projectSettings = settings;
        }
    }

    /** A profile lifecycle that succeeds silently, or fails with {@code failure} when set. */
    private static final class StubLifecycleService extends ProfileLifecycleService {
        private IOException failure;

        @Override
        public void reinstall(String name, Path projectDir) throws IOException {
            if (failure != null) throw failure;
        }
    }

    /** Signals a pending refresh without running a fetch. */
    private static final class StubFetchController extends MarketplaceFetchController {
        private boolean refreshPending;

        StubFetchController(Path projectDir) {
            super(new SettingsService(), new MarketplaceCloneService(),
                HomeIsolatedServices.pluginMapService(projectDir.resolve("no-registry.json")), projectDir);
        }

        @Override
        boolean consumeRefreshPending() {
            var pending = refreshPending;
            refreshPending = false;
            return pending;
        }
    }

    /** Records install requests instead of spawning the Claude Code CLI. */
    private static final class RecordingInstallController extends PluginInstallController {
        private final List<String> installedPlugins = new ArrayList<>();
        private int installEnabledCalls;
        private boolean refreshPending;

        RecordingInstallController(Path projectDir) {
            super(new SettingsService(), new ClaudePluginRegistryService(), new ClaudePluginInstallService(),
                projectDir, (message, seconds) -> { });
        }

        @Override
        void installPlugin(String qualifiedName) {
            installedPlugins.add(qualifiedName);
        }

        @Override
        void installEnabledPlugins() {
            installEnabledCalls++;
        }

        @Override
        boolean consumeRefreshPending() {
            var pending = refreshPending;
            refreshPending = false;
            return pending;
        }
    }

    private static TuiSnapshot snapshotWith(TuiSnapshot.PluginStatus status) {
        return new TuiSnapshot(
            TuiSnapshot.OverviewData.empty(),
            List.of(new TuiSnapshot.ProfileEntry(PROFILE, "a fixture profile", ProfileDriftState.DISABLED, "")),
            List.of(new TuiSnapshot.PluginEntry(PLUGIN, "fixture", "fixture-mkt", status, "a fixture plugin")),
            List.of(),
            List.of());
    }

    /** Builds the app around {@code initial}; later snapshots are set on {@link #dataService}. */
    private void startWith(TuiSnapshot initial) {
        dataService = new CannedDataService(initial);
        var tuiActions = new TuiActions(settingsService, new ClaudeMdService(), dataService, lifecycleService);
        fetch = new StubFetchController(projectDir);
        pluginInstall = new RecordingInstallController(projectDir);
        app = new CcxApp(dataService, tuiActions, fetch, pluginInstall, projectDir);
    }

    /** Feeds one key press through the real dispatcher; the runner is only used by the quit path. */
    private void press(KeyEvent key) {
        app.handleEvent(key, null);
    }

    private void selectTab(Tab tab) {
        press(KeyEvent.ofChar((char) ('1' + tab.index())));
    }

    @BeforeEach
    void setUp() {
        settingsService = new InMemorySettingsService();
        lifecycleService = new StubLifecycleService();
    }

    @Nested
    @DisplayName("plugin toggle install gate")
    class PluginToggle {

        @Test
        @DisplayName("a toggle that enables the plugin installs it")
        void enablingAPlugin_installsIt() {
            startWith(snapshotWith(TuiSnapshot.PluginStatus.AVAILABLE));
            dataService.snapshot = snapshotWith(TuiSnapshot.PluginStatus.PROJECT_ENABLED);

            selectTab(Tab.PLUGINS);
            press(KeyEvent.ofKey(KeyCode.ENTER));

            assertThat(pluginInstall.installedPlugins)
                .as("enabling a plugin in settings.json is not enough — Claude Code cannot load it "
                    + "until the matching install has run")
                .containsExactly(PLUGIN);
        }

        @Test
        @DisplayName("a toggle that disables the plugin installs nothing")
        void disablingAPlugin_installsNothing() {
            startWith(snapshotWith(TuiSnapshot.PluginStatus.PROJECT_ENABLED));
            dataService.snapshot = snapshotWith(TuiSnapshot.PluginStatus.AVAILABLE);

            selectTab(Tab.PLUGINS);
            press(KeyEvent.ofKey(KeyCode.ENTER));

            assertThat(pluginInstall.installedPlugins)
                .as("installing a plugin the user just switched off would spawn a subprocess nobody "
                    + "asked for")
                .isEmpty();
        }

        @Test
        @DisplayName("a failed toggle installs nothing even when the snapshot shows the plugin enabled")
        void failedToggle_installsNothing() {
            startWith(snapshotWith(TuiSnapshot.PluginStatus.AVAILABLE));
            // A stale snapshot can still report the plugin as enabled after the write was refused,
            // so the error is what has to gate the install.
            dataService.snapshot = snapshotWith(TuiSnapshot.PluginStatus.PROJECT_ENABLED);
            settingsService.readFailure = new ConfigReadException("settings.json is not valid JSON",
                new IOException("unexpected end of input"));

            selectTab(Tab.PLUGINS);
            press(KeyEvent.ofKey(KeyCode.ENTER));

            assertThat(pluginInstall.installedPlugins)
                .as("an aborted toggle wrote no enablement, so there is nothing to install")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("profile action install gate")
    class ProfileActions {

        @Test
        @DisplayName("r on a profile reinstalls it and installs the plugins it enables")
        void reinstall_installsEnabledPlugins() {
            startWith(snapshotWith(TuiSnapshot.PluginStatus.AVAILABLE));

            selectTab(Tab.PROFILES);
            press(KeyEvent.ofChar('r'));

            assertThat(pluginInstall.installEnabledCalls)
                .as("a profile writes enabledPlugins for its whole extends closure, and those "
                    + "plugins still need installing")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("a failed reinstall installs nothing")
        void failedReinstall_installsNothing() {
            startWith(snapshotWith(TuiSnapshot.PluginStatus.AVAILABLE));
            lifecycleService.failure = new IOException("context file is read-only");

            selectTab(Tab.PROFILES);
            press(KeyEvent.ofChar('r'));

            assertThat(pluginInstall.installEnabledCalls)
                .as("nothing was applied, so installing the closure would contradict the error the "
                    + "footer just showed")
                .isZero();
        }
    }

    @Nested
    @DisplayName("renderFrame refresh flags")
    class RefreshFlags {

        private Frame testFrame() {
            var area = new Rect(0, 0, 100, 30);
            return Frame.forTesting(Buffer.empty(area));
        }

        @Test
        @DisplayName("a pending fetch and a pending install each rebuild the snapshot exactly once")
        void bothPendingFlags_areConsumed() {
            startWith(snapshotWith(TuiSnapshot.PluginStatus.AVAILABLE));
            var callsBeforeRender = dataService.snapshotCalls;
            fetch.refreshPending = true;
            pluginInstall.refreshPending = true;

            app.renderFrame(testFrame());

            assertThat(dataService.snapshotCalls - callsBeforeRender)
                .as("both controllers signalled a change, so both must be picked up — a flag read "
                    + "but not acted on leaves the rows showing stale state")
                .isEqualTo(2);

            app.renderFrame(testFrame());

            assertThat(dataService.snapshotCalls - callsBeforeRender)
                .as("the flags are one-shot: rebuilding on every frame would put a disk read on the "
                    + "render path")
                .isEqualTo(2);
        }
    }
}
