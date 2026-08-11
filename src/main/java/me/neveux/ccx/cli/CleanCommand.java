package me.neveux.ccx.cli;

import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.ConfigWriteException;
import me.neveux.ccx.service.SettingsService;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import me.neveux.ccx.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;

/**
 * Removes all ccx-managed configuration from a project directory: the ccx
 * marker block in {@code .claude/CLAUDE.md}, enabled plugins in
 * {@code settings.json}, the {@code .claude/rules/} directory, and any legacy
 * {@code .claudeignore} (no longer managed by ccx; deleted so files written by
 * older versions get cleaned up). Without {@code --force} (or when no TTY is
 * available) it performs a dry run, printing what would be removed.
 */
@Command(name = "clean", description = "Remove ccx-managed configuration from project")
@Dependent
@Unremovable
public class CleanCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(CleanCommand.class);

    private final SettingsService settingsService;
    private final ClaudeMdService claudeMdService;

    CleanCommand() {
        this(new SettingsService(), new ClaudeMdService());
    }

    @Inject
    public CleanCommand(SettingsService settingsService, ClaudeMdService claudeMdService) {
        this.settingsService = settingsService;
        this.claudeMdService = claudeMdService;
    }

    @Option(names = {"--force"}, description = "Skip confirmation prompt")
    boolean force;

    @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
    Path projectDir;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        var dir = CommandUtil.resolveProjectDir(projectDir);
        if (!CommandUtil.checkPathSecurity(dir, spec)) return 1;
        var out = spec.commandLine().getOut();

        var claudeMdFile = dir.resolve(".claude/CLAUDE.md");
        var stacks = claudeMdService.readMarkerBlock(claudeMdFile);
        var settings = settingsService.read(dir);

        var claudeIgnore = dir.resolve(".claudeignore");
        var rulesDir = dir.resolve(".claude/rules");

        if (!force && System.console() == null) {
            // No TTY and no --force: print what would be removed without modifying
            out.println("Would remove ccx-managed content (use --force to apply):");
            out.println("  CLAUDE.md ccx marker block:");
            stacks.forEach(l -> out.println("    " + l));
            out.println("  Enabled plugins:");
            if (settings.enabledPlugins() != null) {
                settings.enabledPlugins().keySet().forEach(k -> out.println("    " + k));
            }
            if (Files.exists(claudeIgnore)) out.println("  .claudeignore");
            if (Files.isDirectory(rulesDir)) out.println("  .claude/rules/");
            log.debug("clean dry-run (no TTY) dir={}", dir);
            return 0;
        }

        // With --force: remove ccx-managed content
        removeMarkerBlock(claudeMdFile);

        settingsService.write(settings.withEnabledPlugins(new LinkedHashMap<>()), dir);

        if (Files.exists(claudeIgnore)) {
            try {
                Files.delete(claudeIgnore);
                log.debug(".claudeignore removed path={}", claudeIgnore);
            } catch (IOException e) {
                log.warn("failed to delete .claudeignore path={}", claudeIgnore, e);
            }
        }

        if (Files.isDirectory(rulesDir)) {
            try (var stream = Files.walk(rulesDir)) {
                stream.sorted(Comparator.reverseOrder())
                      .forEach(p -> {
                          try {
                              Files.delete(p);
                          } catch (IOException e) {
                              log.warn("failed to delete rules entry path={}", p, e);
                          }
                      });
                log.debug(".claude/rules/ removed path={}", rulesDir);
            } catch (IOException e) {
                log.warn("failed to remove rules directory path={}", rulesDir, e);
            }
        }

        out.println(CliFormat.green(CliFormat.OK) + " tidied — your .claude/ is back to vanilla");
        log.info("clean completed dir={}", dir);
        return 0;
    }

    private void removeMarkerBlock(Path claudeMdFile) {
        if (!Files.exists(claudeMdFile)) return;
        try {
            var lines = Files.readAllLines(claudeMdFile);
            var output = new ArrayList<String>();
            var inside = false;
            for (var line : lines) {
                if (line.contains("<!-- ccx:begin -->")) {
                    inside = true;
                    continue;
                }
                if (line.contains("<!-- ccx:end -->")) {
                    inside = false;
                    continue;
                }
                if (!inside) {
                    output.add(line);
                }
            }
            // Trim trailing blank lines
            while (!output.isEmpty() && output.get(output.size() - 1).isBlank()) {
                output.remove(output.size() - 1);
            }
            var content = output.isEmpty() ? "" : String.join("\n", output) + "\n";
            FileUtils.atomicWrite(claudeMdFile, content);
            log.debug("removed ccx marker block path={}", claudeMdFile);
        } catch (IOException e) {
            throw new ConfigWriteException("Failed to clean CLAUDE.md at " + claudeMdFile, e);
        }
    }
}
