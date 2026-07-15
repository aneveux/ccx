package me.neveux.ccx.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.neveux.ccx.model.McpServer;
import me.neveux.ccx.tui.Quips;
import me.neveux.ccx.service.SettingsService;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Manages MCP (Model Context Protocol) server entries in a project's
 * {@code .claude/settings.json}. Subcommands allow listing, adding, removing,
 * and inspecting individual server configurations.
 */
@Command(
    name = "mcp",
    description = "Manage MCP servers",
    subcommands = {
        McpCommand.ListCommand.class,
        McpCommand.EnableCommand.class,
        McpCommand.DisableCommand.class,
        McpCommand.ShowCommand.class,
        CommandLine.HelpCommand.class
    },
    mixinStandardHelpOptions = true
)
@Dependent
@Unremovable
@RegisterForReflection
public class McpCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    /** Prints all configured MCP servers sorted alphabetically, showing name, type, and command or URL. */
    @Command(name = "list", description = "List configured MCP servers")
    @RegisterForReflection
    static class ListCommand implements Callable<Integer> {

        @Inject
        SettingsService settingsService;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var dir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(dir, spec)) return 1;
            var settings = settingsService.read(dir);

            var out = spec.commandLine().getOut();

            if (settings.mcpServers() == null || settings.mcpServers().isEmpty()) {
                out.println(Quips.EMPTY[0] + " (no MCP servers configured)");
                return 0;
            }

            settings.mcpServers().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    var serverName = entry.getKey();
                    var server = entry.getValue();
                    String target;
                    if ("http".equals(server.type())) {
                        target = server.url() != null ? server.url() : "";
                    } else {
                        target = server.command() != null ? server.command() : "";
                    }
                    out.println(serverName + "  " + server.type() + "  " + target);
                });
            out.flush();
            return 0;
        }
    }

    /**
     * Adds or replaces an MCP server entry in {@code settings.json}. The server
     * config can be supplied as a raw JSON positional argument or via individual
     * flags ({@code --command}, {@code --args}, {@code --url}, {@code --type}).
     */
    @Command(name = "enable", description = "Add or update an MCP server")
    @RegisterForReflection
    static class EnableCommand implements Callable<Integer> {

        @Inject
        SettingsService settingsService;

        @Inject
        @Named("jsonMapper")
        ObjectMapper mapper;

        @Parameters(index = "0", description = "Server name")
        String name;

        @Parameters(index = "1", arity = "0..1", description = "Server config as JSON (optional; or use --command/--url flags)")
        String jsonString;

        @Option(names = {"--command"}, description = "Command to launch the MCP server")
        String command;

        @Option(names = {"--args"}, description = "Argument for the command (repeatable)")
        List<String> args;

        @Option(names = {"--url"}, description = "URL of the MCP server")
        String url;

        @Option(names = {"--type"}, description = "Server type (e.g. command, http, stdio)")
        String type;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            McpServer server;
            if (jsonString != null && !jsonString.isBlank()) {
                try {
                    server = mapper.readValue(jsonString, McpServer.class);
                } catch (JsonProcessingException e) {
                    spec.commandLine().getErr().println("Invalid JSON: " + e.getMessage());
                    return 1;
                }
            } else if (command != null || url != null || type != null) {
                String resolvedType = type != null ? type
                    : command != null ? "command"
                    : "http";
                server = new McpServer(resolvedType, url, command, args);
            } else {
                spec.commandLine().getErr().println(
                    "Error: provide a server config — use --command (with optional --args), --url, or a JSON positional.");
                return 1;
            }

            var dir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(dir, spec)) return 1;
            var settings = settingsService.read(dir);
            var mcpServers = settings.mcpServers() == null
                ? new LinkedHashMap<String, McpServer>()
                : new LinkedHashMap<>(settings.mcpServers());
            mcpServers.put(name, server);
            settingsService.write(settings.withMcpServers(mcpServers), dir);
            var out = spec.commandLine().getOut();
            out.println("Enabled: " + name);
            out.flush();
            return 0;
        }
    }

    /** Removes the named MCP server from {@code settings.json}. Reports success if the server was not found. */
    @Command(name = "disable", description = "Remove an MCP server")
    @RegisterForReflection
    static class DisableCommand implements Callable<Integer> {

        @Inject
        SettingsService settingsService;

        @Parameters(index = "0", description = "Server name")
        String name;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var dir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(dir, spec)) return 1;
            var settings = settingsService.read(dir);

            if (settings.mcpServers() == null || !settings.mcpServers().containsKey(name)) {
                spec.commandLine().getOut().println("No such server: " + name);
                return 0;
            }

            var mcpServers = new LinkedHashMap<>(settings.mcpServers());
            mcpServers.remove(name);
            settingsService.write(settings.withMcpServers(mcpServers), dir);
            var out = spec.commandLine().getOut();
            out.println("Disabled: " + name);
            out.flush();
            return 0;
        }
    }

    /** Prints the full JSON configuration for a single named MCP server. Exits with code 1 if not found. */
    @Command(name = "show", description = "Show configuration for a specific MCP server")
    @RegisterForReflection
    static class ShowCommand implements Callable<Integer> {

        @Inject
        SettingsService settingsService;

        @Inject
        @Named("jsonMapper")
        ObjectMapper mapper;

        @Parameters(index = "0", description = "Server name")
        String name;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var dir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(dir, spec)) return 1;
            var settings = settingsService.read(dir);

            if (settings.mcpServers() == null || !settings.mcpServers().containsKey(name)) {
                spec.commandLine().getErr().println("MCP server not found: " + name);
                return 1;
            }

            var server = settings.mcpServers().get(name);
            try {
                var json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(server);
                spec.commandLine().getOut().println(json);
                return 0;
            } catch (JsonProcessingException e) {
                spec.commandLine().getErr().println("Failed to serialize server config: " + e.getMessage());
                return 1;
            }
        }
    }
}
