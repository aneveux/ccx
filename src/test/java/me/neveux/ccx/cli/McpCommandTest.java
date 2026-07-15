// Wave 1 — TDD baseline: tests MUST fail until McpCommand is implemented
package me.neveux.ccx.cli;

import me.neveux.ccx.model.McpServer;
import me.neveux.ccx.model.Settings;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.PluginMapService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("McpCommand")
class McpCommandTest {

    @TempDir
    Path projectDir;

    private SettingsService settingsService;
    private CommandLine commandLine;
    private final ByteArrayOutputStream outputCapture = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        settingsService = new SettingsService();
        var factory = new TestCommandFactory(
            settingsService, new ProfileService(), new PluginMapService(),
            new ClaudeMdService()
        );
        commandLine = new CommandLine(new CcxCommand(), factory);
        commandLine.setOut(new PrintWriter(outputCapture, true));
        commandLine.setErr(new PrintWriter(outputCapture, true));
    }

    private int run(String... args) {
        outputCapture.reset();
        return commandLine.execute(args);
    }

    private String output() {
        return outputCapture.toString();
    }

    private McpServer httpServer(String url) {
        return new McpServer("http", url, null, null);
    }

    private McpServer commandServer(String command) {
        return new McpServer("command", null, command, null);
    }

    private void writeSettings(Settings settings) {
        settingsService.write(settings, projectDir);
    }

    @Nested
    @DisplayName("mcp list")
    class List_ {

        @Test
        @DisplayName("should print placeholder when no MCP servers are configured")
        void list_noServers_printsFallbackMessage() {
            int exit = run("mcp", "list", "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("exit code must be 0 when no servers configured")
                .isEqualTo(0);
            assertThat(output())
                .as("output must contain the empty-state message")
                .contains("(no MCP servers configured)");
        }

        @Test
        @DisplayName("should print name, type, and URL for an http server")
        void list_httpServer_printsNameTypeUrl() {
            var settings = new Settings().withMcpServers(Map.of("my-api", httpServer("https://api.example.com/mcp")));
            writeSettings(settings);

            int exit = run("mcp", "list", "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("exit code must be 0")
                .isEqualTo(0);
            assertThat(output())
                .as("output must contain server name")
                .contains("my-api");
            assertThat(output())
                .as("output must contain type 'http'")
                .contains("http");
            assertThat(output())
                .as("output must contain URL with two-space separator")
                .contains("my-api  http  https://api.example.com/mcp");
        }

        @Test
        @DisplayName("should print name, type, and command path for a command server")
        void list_commandServer_printsNameTypeCommand() {
            var settings = new Settings().withMcpServers(Map.of("local-tool", commandServer("/usr/bin/my-mcp-tool")));
            writeSettings(settings);

            int exit = run("mcp", "list", "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("exit code must be 0")
                .isEqualTo(0);
            assertThat(output())
                .as("output must contain server name")
                .contains("local-tool");
            assertThat(output())
                .as("output must contain type 'command'")
                .contains("command");
            assertThat(output())
                .as("output must contain command path with two-space separator")
                .contains("local-tool  command  /usr/bin/my-mcp-tool");
        }
    }

    @Nested
    @DisplayName("mcp enable")
    class Enable {

        @Test
        @DisplayName("should add server to settings and print confirmation on valid JSON")
        void enable_validJson_addsServerAndPrintsConfirmation() {
            String json = "{\"type\":\"http\",\"url\":\"https://mcp.example.com\"}";

            int exit = run("mcp", "enable", "new-server", json,
                "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("exit code must be 0 on valid JSON")
                .isEqualTo(0);
            assertThat(output())
                .as("output must confirm the server was enabled")
                .contains("Enabled: new-server");

            var written = settingsService.read(projectDir);
            assertThat(written.mcpServers())
                .as("settings must contain the newly enabled server")
                .containsKey("new-server");
            assertThat(written.mcpServers().get("new-server").type())
                .as("stored server type must match JSON input")
                .isEqualTo("http");
            assertThat(written.mcpServers().get("new-server").url())
                .as("stored server URL must match JSON input")
                .isEqualTo("https://mcp.example.com");
        }

        @Test
        @DisplayName("should exit 1 and print error on invalid JSON")
        void enable_invalidJson_exits1WithError() {
            int exit = run("mcp", "enable", "broken-server", "NOT_VALID_JSON",
                "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("exit code must be 1 on invalid JSON")
                .isEqualTo(1);
            assertThat(output())
                .as("output must report the JSON could not be parsed")
                .containsIgnoringCase("Invalid JSON");
        }
    }

    @Nested
    @DisplayName("mcp enable — flag-based UX")
    class EnableFlags {

        @Test
        @DisplayName("should build a command server from --command and repeatable --args, no JSON")
        void enable_commandAndArgs_buildsCommandServer() {
            int exit = run("mcp", "enable", "fs",
                "--command", "node",
                "--args", "server.js",
                "--args", "--port",
                "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("mcp enable with --command/--args must exit 0")
                .isEqualTo(0);

            var server = settingsService.read(projectDir).mcpServers().get("fs");
            assertThat(server)
                .as("flag-based enable must write a server named 'fs'")
                .isNotNull();
            assertThat(server.command())
                .as("--command value must be stored as the server command")
                .isEqualTo("node");
            assertThat(server.args())
                .as("each --args occurrence must add one entry to the args list")
                .contains("server.js", "--port");
        }

        @Test
        @DisplayName("should default the server type to 'command' when --command is used without --type")
        void enable_commandWithoutType_defaultsTypeCommand() {
            int exit = run("mcp", "enable", "fs",
                "--command", "node",
                "--args", "server.js",
                "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("mcp enable with --command must exit 0")
                .isEqualTo(0);

            var server = settingsService.read(projectDir).mcpServers().get("fs");
            assertThat(server)
                .as("flag-based enable must write a server named 'fs'")
                .isNotNull();
            assertThat(server.type())
                .as("type must default to 'command' when --command is given and --type omitted")
                .isEqualTo("command");
        }

        @Test
        @DisplayName("should build an http server from --type and --url, no JSON")
        void enable_typeAndUrl_buildsHttpServer() {
            int exit = run("mcp", "enable", "api",
                "--type", "http",
                "--url", "https://api.example.com/mcp",
                "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("mcp enable with --type/--url must exit 0")
                .isEqualTo(0);

            var server = settingsService.read(projectDir).mcpServers().get("api");
            assertThat(server)
                .as("flag-based enable must write a server named 'api'")
                .isNotNull();
            assertThat(server.type())
                .as("--type value must be stored as the server type")
                .isEqualTo("http");
            assertThat(server.url())
                .as("--url value must be stored as the server URL")
                .isEqualTo("https://api.example.com/mcp");
        }

        @Test
        @DisplayName("should still accept the legacy JSON positional when no flags are given")
        void enable_jsonPositional_stillWorks() {
            int exit = run("mcp", "enable", "x", "{\"command\":\"foo\"}",
                "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("mcp enable with a JSON positional must still exit 0")
                .isEqualTo(0);

            var server = settingsService.read(projectDir).mcpServers().get("x");
            assertThat(server)
                .as("the JSON-positional path must still create the server")
                .isNotNull();
            assertThat(server.command())
                .as("the JSON command field must be parsed and stored")
                .isEqualTo("foo");
        }

        @Test
        @DisplayName("should exit 1 with an error when neither flags nor JSON are provided")
        void enable_noFlagsNoJson_exits1WithError() {
            int exit = run("mcp", "enable", "empty",
                "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("mcp enable with no flags and no JSON must exit non-zero")
                .isEqualTo(1);
            assertThat(output())
                .as("error must guide the user toward providing a command, url, or JSON")
                .containsIgnoringCase("command");
        }
    }

    @Nested
    @DisplayName("mcp disable")
    class Disable {

        @Test
        @DisplayName("should remove server from settings and print confirmation when server exists")
        void disable_existingServer_removesAndPrintsConfirmation() {
            var settings = new Settings().withMcpServers(new LinkedHashMap<>(Map.of("to-remove", httpServer("https://gone.example.com"))));
            writeSettings(settings);

            int exit = run("mcp", "disable", "to-remove",
                "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("exit code must be 0 when server exists")
                .isEqualTo(0);
            assertThat(output())
                .as("output must confirm the server was disabled")
                .contains("Disabled: to-remove");

            var written = settingsService.read(projectDir);
            assertThat(written.mcpServers())
                .as("settings must no longer contain the removed server")
                .doesNotContainKey("to-remove");
        }

        @Test
        @DisplayName("should exit 0 and print not-found message when server does not exist")
        void disable_absentServer_exits0WithNotFoundMessage() {
            int exit = run("mcp", "disable", "ghost-server",
                "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("exit code must be 0 even when server is absent")
                .isEqualTo(0);
            assertThat(output())
                .as("output must indicate the server was not found")
                .contains("No such server: ghost-server");
        }
    }

    @Nested
    @DisplayName("mcp show")
    class Show {

        @Test
        @DisplayName("should exit 0 and print server config when server exists")
        void show_existingServer_exits0AndPrintsConfig() {
            var settings = new Settings();
            var server = new McpServer("stdio", null, "/opt/tools/mcp-stdio", List.of("--verbose"));
            settings = settings.withMcpServers(Map.of("stdio-server", server));
            writeSettings(settings);

            int exit = run("mcp", "show", "stdio-server",
                "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("exit code must be 0 when server is found")
                .isEqualTo(0);
            assertThat(output())
                .as("output must contain the server type")
                .contains("stdio");
            assertThat(output())
                .as("output must contain the server command path")
                .contains("/opt/tools/mcp-stdio");
            assertThat(output())
                .as("output must contain the server args")
                .contains("--verbose");
        }

        @Test
        @DisplayName("should exit 1 and print error message when server does not exist")
        void show_absentServer_exits1WithErrorMessage() {
            int exit = run("mcp", "show", "no-such-server",
                "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("exit code must be 1 when server is not found")
                .isEqualTo(1);
            assertThat(output())
                .as("output must contain the not-found error message")
                .contains("MCP server not found: no-such-server");
        }
    }
}
