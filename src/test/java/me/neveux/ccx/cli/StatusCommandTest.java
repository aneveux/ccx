// Wave 3 — Commands
package me.neveux.ccx.cli;

import me.neveux.ccx.model.McpServer;
import me.neveux.ccx.model.Settings;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.HomeIsolatedServices;
import me.neveux.ccx.service.ProfileService;
import me.neveux.ccx.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("StatusCommand")
class StatusCommandTest {

    @TempDir
    Path tempDir;

    private SettingsService settingsService;
    private CommandLine commandLine;

    @BeforeEach
    void setUp() {
        settingsService = new SettingsService();

        var factory = new TestCommandFactory(
            settingsService, new ProfileService(),
            HomeIsolatedServices.pluginMapService(tempDir.resolve("no-registry.json")),
            new ClaudeMdService()
        );
        commandLine = new CommandLine(new CcxCommand(), factory);
    }

    private record RunResult(int exitCode, String output) {}

    private RunResult run(String... args) {
        var out = new ByteArrayOutputStream();
        commandLine.setOut(new PrintWriter(out, true));
        var exit = commandLine.execute(args);
        return new RunResult(exit, out.toString());
    }

    @Test
    @DisplayName("should exit 0 and give empty sections the shared Quips.EMPTY treatment when no settings file exists")
    void statusOnEmptyProject_showsNoneSections() {
        var result = run("status", "--project-dir", tempDir.toString());

        assertThat(result.exitCode())
            .as("status on empty project must exit 0")
            .isZero();
        assertThat(result.output())
            .as("empty sections must use the shared Quips.EMPTY voice, not a bare (none)")
            .contains(me.neveux.ccx.tui.Quips.EMPTY[0]);
    }

    @Test
    @DisplayName("should exit 0 and show all section headers for an initialized project")
    void statusOnInitializedProject_showsAllSections() throws Exception {
        var settings = buildPopulatedSettings();
        settingsService.write(settings, tempDir);

        var result = run("status", "--project-dir", tempDir.toString());

        assertThat(result.exitCode())
            .as("status on initialized project must exit 0")
            .isZero();
        assertThat(result.output()).contains("Plugins:");
        assertThat(result.output()).contains("Stacks:");
        assertThat(result.output()).contains("Skill overrides:");
        assertThat(result.output()).contains("MCP servers:");
        assertThat(result.output())
            .as("an initialized project must NOT show the empty-config guidance")
            .doesNotContain("No ccx configuration yet");
    }

    @Test
    @DisplayName("should exit 0 and show plugin name when settings has enabled plugins")
    void statusShowsPluginNames() throws Exception {
        var settings = new Settings().withEnabledPlugins(Map.of("java@claude-java-stack", true));
        settingsService.write(settings, tempDir);

        var result = run("status", "--project-dir", tempDir.toString());

        assertThat(result.exitCode())
            .as("status must exit 0 when settings has enabled plugins")
            .isZero();
        assertThat(result.output())
            .as("status must display enabled plugin names")
            .contains("java@claude-java-stack");
    }

    @Test
    @DisplayName("should not throw NPE when spec is not injected (null-spec guard)")
    void statusCommand_withNullSpec_doesNotThrowNpe() {
        var cmd = new StatusCommand(new SettingsService(), new ClaudeMdService());
        // spec field is not set — simulates CDI proxy instantiation before picocli wires spec
        assertThatCode(() -> cmd.call())
            .as("StatusCommand.call() must not NPE when @CommandLine.Spec has not been injected")
            .doesNotThrowAnyException();
    }

    @Nested
    @DisplayName("Wave 7 — status --full")
    class Full {

        @TempDir
        Path globalTempDir;

        @Test
        @DisplayName("project-only run (no --full) must not emit a Global section")
        void statusWithoutFull_omitsGlobalSection() throws Exception {
            // a populated project so the empty-config guidance never fires and masks the assertion
            settingsService.write(buildPopulatedSettings(), tempDir);

            var result = run("status", "--project-dir", tempDir.toString());

            assertThat(result.exitCode())
                .as("project-only status must exit 0")
                .isZero();
            assertThat(result.output())
                .as("without --full the output is project-only and must not include a Global section")
                .doesNotContain("Global");
        }

        @Test
        @DisplayName("--full reads --global-dir and shows the Global section with its enabled plugin")
        void statusWithFull_showsGlobalSectionAndPlugin() throws Exception {
            settingsService.write(buildPopulatedSettings(), tempDir);
            var globalSettings = new Settings().withEnabledPlugins(Map.of("globaltool@mkt", true));
            settingsService.write(globalSettings, globalTempDir);

            var result = run("status", "--project-dir", tempDir.toString(),
                "--full", "--global-dir", globalTempDir.toString());

            assertThat(result.exitCode())
                .as("status --full must exit 0")
                .isZero();
            assertThat(result.output())
                .as("--full must append a Global section header")
                .contains("Global");
            assertThat(result.output())
                .as("--full must surface the globally enabled plugin name read from --global-dir")
                .contains("globaltool@mkt");
        }
    }

    @Nested
    @DisplayName("Wave 7 — empty-config guidance")
    class EmptyGuidance {

        @TempDir
        Path globalTempDir;

        @Test
        @DisplayName("empty project (no --full) prints onboarding guidance")
        void emptyProject_printsGuidance() {
            var result = run("status", "--project-dir", tempDir.toString());

            assertThat(result.exitCode())
                .as("status on empty project must exit 0")
                .isZero();
            assertThat(result.output())
                .as("empty config must surface the onboarding guidance line")
                .contains("No ccx configuration yet")
                .contains("ccx profiles new");
        }

        @Test
        @DisplayName("empty project + empty global (--full) still prints onboarding guidance")
        void emptyProjectAndGlobal_printsGuidance() {
            var result = run("status", "--project-dir", tempDir.toString(),
                "--full", "--global-dir", globalTempDir.toString());

            assertThat(result.exitCode())
                .as("status --full on an empty project + empty global must exit 0")
                .isZero();
            assertThat(result.output())
                .as("empty project + empty global must still surface the onboarding guidance line")
                .contains("No ccx configuration yet")
                .contains("ccx profiles new");
        }
    }

    private Settings buildPopulatedSettings() {
        var mcp = new McpServer("sse", "http://localhost:8080/sse", null, null);
        var settings = new Settings()
            .withEnabledPlugins(Map.of("java@claude-java-stack", true))
            .withSkillOverrides(Map.of("java-testing", "user-invocable-only"))
            .withMcpServers(Map.of("my-server", mcp));
        return settings;
    }

    // -----------------------------------------------------------------------
    // Plan 046 — .claudeignore line removed from status output
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Plan 046 — no .claudeignore line in status output")
    class NoDotClaudeIgnoreLine {

        @Test
        @DisplayName("status output must not mention .claudeignore after migration to deny rules")
        void status_doesNotMentionClaudeIgnore() {
            var result = run("status", "--project-dir", tempDir.toString());

            assertThat(result.output())
                .as("status must not mention .claudeignore — "
                    + "ignore patterns now live in permissions.deny, not a separate file")
                .doesNotContain(".claudeignore");
        }
    }
}
