package me.neveux.ccx.cli;

import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.SettingsService;
import me.neveux.ccx.tui.Quips;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.Callable;

/**
 * Prints a concise overview of the current project's ccx state: enabled plugins,
 * CLAUDE.md stack references, skill overrides, MCP servers, {@code .claudeignore}
 * presence, and rule files. With {@code --full} also shows the global
 * ({@code ~/.claude}) configuration alongside the project view.
 */
@Command(name = "status", description = "Show current ccx configuration status")
@Dependent
@Unremovable
public class StatusCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(StatusCommand.class);

    private final SettingsService settingsService;
    private final ClaudeMdService claudeMdService;

    StatusCommand() {
        this(new SettingsService(), new ClaudeMdService());
    }

    @Inject
    public StatusCommand(SettingsService settingsService, ClaudeMdService claudeMdService) {
        this.settingsService = settingsService;
        this.claudeMdService = claudeMdService;
    }

    @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
    Path projectDir;

    @Option(names = {"--full"}, description = "Also show global (~/.claude) configuration")
    boolean full;

    @Option(names = {"--global-dir"}, description = "Global config dir (defaults to user.home)")
    Path globalDir;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        var dir = CommandUtil.resolveProjectDir(projectDir);
        if (!CommandUtil.checkPathSecurity(dir, spec)) return 1;
        if (globalDir != null && !CommandUtil.checkPathSecurity(globalDir, spec, "--global-dir")) return 1;
        var settings = settingsService.read(dir);
        var stacks = claudeMdService.readMarkerBlock(dir.resolve(".claude/CLAUDE.md"));

        var out = spec != null ? spec.commandLine().getOut() : new PrintWriter(System.out, true);

        var enabledPlugins = settings.enabledPlugins() != null ? settings.enabledPlugins().keySet() : null;
        var skillOverrides = settings.skillOverrides() != null ? settings.skillOverrides().keySet() : null;
        var mcpServers = settings.mcpServers() != null ? settings.mcpServers().keySet() : null;

        out.println(CliFormat.dim("Plugins:"));
        printItems(out, enabledPlugins);

        out.println(CliFormat.dim("Stacks:"));
        printItems(out, stacks.isEmpty() ? null : stacks);

        out.println(CliFormat.dim("Skill overrides:"));
        printItems(out, skillOverrides);

        out.println(CliFormat.dim("MCP servers:"));
        printItems(out, mcpServers);

        // .claudeignore
        var claudeIgnore = dir.resolve(".claudeignore");
        var claudeIgnorePresent = Files.exists(claudeIgnore);
        if (claudeIgnorePresent) {
            long entryCount;
            try (var lines = Files.lines(claudeIgnore)) {
                entryCount = lines.filter(l -> !l.isBlank() && !l.startsWith("#")).count();
            } catch (IOException e) {
                log.warn("could not count .claudeignore entries path={}", claudeIgnore, e);
                entryCount = 0;
            }
            out.println(".claudeignore: " + CliFormat.green("present") + " (" + entryCount + " entries)");
        } else {
            out.println(".claudeignore: " + CliFormat.dim("absent"));
        }

        // Rules files
        var rulesDir = dir.resolve(".claude/rules");
        var hasRulesFiles = false;
        out.println(CliFormat.dim("Rules:"));
        if (Files.isDirectory(rulesDir)) {
            try (var files = Files.list(rulesDir)) {
                var ruleLines = files.filter(Files::isRegularFile)
                     .map(p -> "  " + CliFormat.ENABLED + " " + p.getFileName().toString())
                     .sorted()
                     .toList();
                hasRulesFiles = !ruleLines.isEmpty();
                ruleLines.forEach(out::println);
            } catch (IOException e) {
                out.println("  (error listing rules dir)");
            }
        } else {
            out.println("  " + Quips.EMPTY[0] + " (none)");
        }

        var projectEmpty = isEmpty(enabledPlugins) && stacks.isEmpty() && isEmpty(skillOverrides)
            && isEmpty(mcpServers) && !claudeIgnorePresent && !hasRulesFiles;

        var globalEmpty = true;
        if (full) {
            var globalRoot = globalDir != null ? globalDir : Path.of(System.getProperty("user.home"));
            var globalSettings = settingsService.read(globalRoot);
            var globalPlugins = globalSettings.enabledPlugins() != null ? globalSettings.enabledPlugins().keySet() : null;
            var globalSkills = globalSettings.skillOverrides() != null ? globalSettings.skillOverrides().keySet() : null;
            var globalMcp = globalSettings.mcpServers() != null ? globalSettings.mcpServers().keySet() : null;
            globalEmpty = isEmpty(globalPlugins) && isEmpty(globalSkills) && isEmpty(globalMcp);

            out.println();
            out.println(CliFormat.dim("=== Global ==="));
            out.println(CliFormat.dim("Plugins:"));
            printItems(out, globalPlugins);
            out.println(CliFormat.dim("Skill overrides:"));
            printItems(out, globalSkills);
            out.println(CliFormat.dim("MCP servers:"));
            printItems(out, globalMcp);
        }

        if (projectEmpty && (!full || globalEmpty)) {
            out.println("No ccx configuration yet. Run `ccx profiles new <name>` to create your first profile.");
        }

        log.debug("status displayed dir={}", dir);
        return 0;
    }

    private static boolean isEmpty(Collection<String> items) {
        return items == null || items.isEmpty();
    }

    private void printItems(PrintWriter out, Collection<String> items) {
        if (items == null || items.isEmpty()) {
            out.println("  " + Quips.EMPTY[0] + " (none)");
        } else {
            items.forEach(item -> out.println("  " + CliFormat.green(CliFormat.ENABLED) + " " + item));
        }
    }
}
