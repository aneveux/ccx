// Wave 4 — F39: TuiCommand no-TTY guard (lives in tui package to construct TuiDataService)
package me.neveux.ccx.tui;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.neveux.ccx.cli.TuiCommand;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.HomeIsolatedServices;
import me.neveux.ccx.service.MarketplaceCloneService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.ProfileLifecycleService;
import me.neveux.ccx.service.SettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TuiCommand — no-TTY guard")
class TuiCommandNoTtyTest {

    @TempDir
    Path tempDir;

    /** Never reads the developer's real marketplace registry — see {@link HomeIsolatedServices}. */
    private PluginMapService isolatedPluginMapService() {
        return HomeIsolatedServices.pluginMapService(tempDir.resolve("no-registry.json"));
    }

    @Test
    @DisplayName("exits 1 requiring a TTY when no console is attached")
    void tui_noTty_exitsRequiringTty() {
        var buf = new ByteArrayOutputStream();
        var settingsService = new SettingsService();
        var claudeMdService = new ClaudeMdService();
        // TuiDataService constructors are package-private — accessible from this tui-package test.
        var tuiDataService = new TuiDataService(settingsService, claudeMdService, new ObjectMapper());
        var cmd = new TuiCommand(
            tuiDataService, settingsService, claudeMdService,
            new ProfileLifecycleService(),
            new MarketplaceCloneService(), isolatedPluginMapService());

        var cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(buf, true));
        cl.setErr(new PrintWriter(buf, true));

        // Test runs have no console; the no-TTY guard must fire.
        int exit = cl.execute("--project-dir", ".");

        assertThat(exit)
            .as("TUI must exit 1 when no TTY is available (test runs have no console)")
            .isEqualTo(1);
        assertThat(buf.toString())
            .as("TUI must explain that a TTY is required")
            .containsIgnoringCase("TTY");
    }

    @Test
    @DisplayName("rejects --project-dir outside allowed directories before reaching the TTY guard")
    void tui_projectDirOutsideAllowedDirs_exitsRejecting() {
        var buf = new ByteArrayOutputStream();
        var settingsService = new SettingsService();
        var claudeMdService = new ClaudeMdService();
        // Real services required: the no-arg ctor leaves them null and would hit the
        // null-services guard first. The path-security guard sits between that and the no-TTY guard.
        var tuiDataService = new TuiDataService(settingsService, claudeMdService, new ObjectMapper());
        var cmd = new TuiCommand(
            tuiDataService, settingsService, claudeMdService,
            new ProfileLifecycleService(),
            new MarketplaceCloneService(), isolatedPluginMapService());

        var cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(buf, true));
        cl.setErr(new PrintWriter(buf, true));

        int exit = cl.execute("--project-dir", "/etc");

        assertThat(exit)
            .as("TUI must exit 1 when --project-dir is outside allowed directories")
            .isEqualTo(1);
        assertThat(buf.toString())
            .as("TUI must report that --project-dir is outside allowed directories, not fall through to the TTY guard")
            .containsIgnoringCase("outside allowed");
    }
}
