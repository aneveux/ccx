package me.neveux.ccx.cli;

import me.neveux.ccx.model.Settings;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.ProfileService;
import me.neveux.ccx.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CcxCommand — first-run banner onboarding")
class CcxCommandFirstRunTest {

    @TempDir
    Path tempDir;

    private SettingsService settingsService;
    private TestCommandFactory factory;

    @BeforeEach
    void setUp() {
        settingsService = new SettingsService();
        factory = new TestCommandFactory(
            settingsService, new ProfileService(), new PluginMapService(),
            new ClaudeMdService()
        );
    }

    private record RunResult(int exitCode, String output) {}

    private RunResult run(CommandLine commandLine, String... args) {
        var out = new ByteArrayOutputStream();
        commandLine.setOut(new PrintWriter(out, true));
        var exit = commandLine.execute(args);
        return new RunResult(exit, out.toString());
    }

    @Test
    @DisplayName("should show the create-profile nudge when no profiles are available")
    void bare_emptyProfiles_showsStarterBlock() {
        var root = new CcxCommand();
        root.availableProfilesSupplier = List::of;
        var commandLine = new CommandLine(root, factory);

        var result = run(commandLine, "--project-dir", tempDir.toString());

        assertThat(result.output())
            .as("empty profiles must prompt users to create their first profile")
            .contains("ccx profiles new");
    }

    @Test
    @DisplayName("should keep the profiles-new nudge and omit the starter block when profiles exist")
    void bare_nonEmptyProfiles_showsNudgeNotStarterBlock() throws Exception {
        var root = new CcxCommand();
        root.availableProfilesSupplier = () -> List.of("java");
        var commandLine = new CommandLine(root, factory);

        // no plugins enabled
        settingsService.write(new Settings(), tempDir);

        var result = run(commandLine, "--project-dir", tempDir.toString());

        assertThat(result.output())
            .as("with profiles available the existing profiles-new nudge stays")
            .contains("profiles new");
        assertThat(result.output())
            .as("with profiles available the starter cp -r block must NOT appear")
            .doesNotContain("cp -r");
    }
}
