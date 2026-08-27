package me.neveux.ccx.cli;

import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.MarketplaceCloneService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.ProfileLifecycleService;
import me.neveux.ccx.service.SettingsService;
import me.neveux.ccx.tui.CcxApp;
import me.neveux.ccx.tui.TuiDataService;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Launches the full-screen Tamboui TUI ({@link me.neveux.ccx.tui.CcxApp}) for
 * interactive Claude Code configuration. Requires an interactive TTY; exits with
 * an error if running in CI or without a terminal. All TUI services are injected
 * by CDI and validated before the dashboard is started.
 */
@Dependent
@Unremovable
@Command(name = "tui", description = "Interactive TUI for Claude config")
public class TuiCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(TuiCommand.class);

    private final TuiDataService tuiDataService;
    private final SettingsService settingsService;
    private final ClaudeMdService claudeMdService;
    private final ProfileLifecycleService profileLifecycleService;
    private final MarketplaceCloneService marketplaceCloneService;
    private final PluginMapService pluginMapService;

    @Spec
    CommandSpec spec;

    @Option(names = {"--project-dir"}, description = "Project directory", defaultValue = ".")
    Path projectDir;

    @Inject
    public TuiCommand(TuiDataService tuiDataService, SettingsService settingsService,
                      ClaudeMdService claudeMdService, ProfileLifecycleService profileLifecycleService,
                      MarketplaceCloneService marketplaceCloneService, PluginMapService pluginMapService) {
        this.tuiDataService = tuiDataService;
        this.settingsService = settingsService;
        this.claudeMdService = claudeMdService;
        this.profileLifecycleService = profileLifecycleService;
        this.marketplaceCloneService = marketplaceCloneService;
        this.pluginMapService = pluginMapService;
    }

    TuiCommand() {
        this.tuiDataService = null;
        this.settingsService = null;
        this.claudeMdService = null;
        this.profileLifecycleService = null;
        this.marketplaceCloneService = null;
        this.pluginMapService = null;
    }

    @Override
    public Integer call() throws Exception {
        if (tuiDataService == null || settingsService == null || claudeMdService == null
                || profileLifecycleService == null
                || marketplaceCloneService == null || pluginMapService == null) {
            spec.commandLine().getErr().println("Error: TUI services unavailable (CDI injection failed)");
            return 1;
        }
        var resolved = CommandUtil.resolveProjectDir(projectDir).toAbsolutePath().normalize();
        if (!CommandUtil.checkPathSecurity(resolved, spec)) return 1;
        if (System.console() == null || "1".equals(System.getenv("CI"))) {
            spec.commandLine().getErr().println("TUI requires a TTY");
            return 1;
        }

        log.info("Starting TUI projectDir={}", projectDir);
        var app = new CcxApp(tuiDataService, settingsService, claudeMdService,
            profileLifecycleService, marketplaceCloneService, pluginMapService, resolved);
        app.run();
        return 0;
    }
}
