// Wave 4 — F20/F38: read-command path security
package me.neveux.ccx.cli;

import me.neveux.ccx.service.CcxConfigService;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.ProfileService;
import me.neveux.ccx.service.SettingsService;
import me.neveux.ccx.service.SkillsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 4 — F20: every read-only command must reject a --project-dir / --global-dir
 * outside the allowed roots (home, tmp, cwd) with an "outside allowed" message.
 * Implemented as a single parameterized test (also satisfies F38).
 */
@DisplayName("Read-command path security")
class ReadCommandSecurityTest {

    private CommandLine commandLine;
    private final ByteArrayOutputStream outputCapture = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        var settingsService = new SettingsService();
        var factory = new TestCommandFactory(
            settingsService, new ProfileService(), new PluginMapService(),
            new ClaudeMdService(), new SkillsService(settingsService),
            null, null, new CcxConfigService()
        );
        commandLine = new CommandLine(new CcxCommand(), factory);
        commandLine.setOut(new PrintWriter(outputCapture, true));
        commandLine.setErr(new PrintWriter(outputCapture, true));
    }

    static Stream<Arguments> outsideRootInvocations() {
        // Each entry is the full argv for a read-only command pointed at /etc (outside roots).
        return Stream.of(
            Arguments.of("plugins list", new String[]{"plugins", "list", "--project-dir", "/etc"}),
            Arguments.of("plugins tags", new String[]{"plugins", "tags", "--project-dir", "/etc"}),
            Arguments.of("plugins diff", new String[]{"plugins", "diff", "--project-dir", "/etc"}),
            Arguments.of("mcp list", new String[]{"mcp", "list", "--project-dir", "/etc"}),
            Arguments.of("mcp show", new String[]{"mcp", "show", "some-server", "--project-dir", "/etc"}),
            Arguments.of("status", new String[]{"status", "--project-dir", "/etc"}),
            Arguments.of("skills list", new String[]{"skills", "list", "--project-dir", "/etc"}),
            Arguments.of("doctor", new String[]{"doctor", "--project-dir", "/etc"})
        );
    }

    @ParameterizedTest(name = "{0} rejects --project-dir outside allowed roots")
    @MethodSource("outsideRootInvocations")
    @DisplayName("read command rejects --project-dir outside allowed roots")
    void readCommand_rejectsProjectDirOutsideAllowedRoots(String label, String[] argv) {
        outputCapture.reset();

        commandLine.execute(argv);

        assertThat(outputCapture.toString())
            .as("%s with --project-dir=/etc must report 'outside allowed'", label)
            .containsIgnoringCase("outside allowed");
    }
}
