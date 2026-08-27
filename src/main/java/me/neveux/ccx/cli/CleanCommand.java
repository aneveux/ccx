package me.neveux.ccx.cli;

import me.neveux.ccx.model.CcxContext;
import me.neveux.ccx.service.CcxContextService;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.ConfigWriteException;
import me.neveux.ccx.service.ProfileApplyService;
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
 * Removes all ccx-managed configuration from a project directory: every profile recorded in
 * {@code .ccx/context.json} is reverted (its permissions, env entries, rule files, command files
 * and CLAUDE.md fragments), then the context file itself, the ccx marker block in
 * {@code .claude/CLAUDE.md}, the remaining enabled plugins in {@code settings.json}, the
 * {@code .claude/rules/} directory and any legacy {@code .claudeignore} are removed.
 *
 * <p>Nothing is ever modified without {@code --force}: a bare {@code ccx clean} always prints
 * what it would remove and exits. There is no interactive prompt, so the command behaves
 * identically in a terminal, a pipe and CI.
 */
@Command(name = "clean", description = "Remove ccx-managed configuration from project")
@Dependent
@Unremovable
public class CleanCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(CleanCommand.class);

    private final SettingsService settingsService;
    private final ClaudeMdService claudeMdService;
    private final ProfileApplyService profileApplyService;
    private final CcxContextService ccxContextService;

    CleanCommand() {
        this(new SettingsService(), new ClaudeMdService(), new ProfileApplyService(), new CcxContextService());
    }

    @Inject
    public CleanCommand(SettingsService settingsService, ClaudeMdService claudeMdService,
                        ProfileApplyService profileApplyService, CcxContextService ccxContextService) {
        this.settingsService = settingsService;
        this.claudeMdService = claudeMdService;
        this.profileApplyService = profileApplyService;
        this.ccxContextService = ccxContextService;
    }

    @Option(names = {"--force"},
        description = "Actually remove the configuration; without it, clean only reports what it would remove")
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
        var context = ccxContextService.read(dir);

        var claudeIgnore = dir.resolve(".claudeignore");
        var rulesDir = dir.resolve(".claude/rules");
        var contextFile = dir.resolve(".ccx/context.json");

        if (!force) {
            var settings = settingsService.read(dir);
            out.println("Would remove ccx-managed content (use --force to apply):");
            if (!context.enabledProfiles().isEmpty()) {
                out.println("  Profiles to revert (permissions, env, rules, commands, fragments):");
                context.enabledProfiles().keySet().stream().sorted().forEach(name -> out.println("    " + name));
            }
            out.println("  CLAUDE.md ccx marker block:");
            stacks.forEach(l -> out.println("    " + l));
            out.println("  Enabled plugins:");
            if (settings.enabledPlugins() != null) {
                settings.enabledPlugins().keySet().forEach(k -> out.println("    " + k));
            }
            if (Files.exists(claudeIgnore)) out.println("  .claudeignore");
            if (Files.isDirectory(rulesDir)) out.println("  .claude/rules/");
            if (Files.exists(contextFile)) out.println("  .ccx/context.json");
            out.flush();
            log.debug("clean dry-run dir={}", dir);
            return 0;
        }

        // Refuse an unparseable settings.json before touching anything: a half-cleaned project is
        // worse than a refused clean, and every step below ends in a settings.json write.
        settingsService.readForUpdate(dir);

        // Revert every recorded profile first, so the resources only the snapshot knows about
        // (permissions, env, command files, fragments) are undone rather than stranded.
        revertRecordedProfiles(context, dir);
        deleteContextFile(contextFile);

        removeMarkerBlock(claudeMdFile);

        // Re-read: reverting the profiles above rewrote settings.json.
        var settings = settingsService.readForUpdate(dir);
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

    /**
     * Undoes every profile recorded in {@code context}, using the snapshot-driven removal path so
     * permissions, env entries, command files and fragments go with them. The unfiltered overload
     * is deliberate: clean removes all profiles, so there is no sibling left to preserve anything.
     */
    private void revertRecordedProfiles(CcxContext context, Path dir) {
        for (var entry : context.enabledProfiles().entrySet()) {
            var record = entry.getValue();
            profileApplyService.remove(record != null ? record.snapshot() : null, dir);
            log.debug("profile reverted by clean name={}", entry.getKey());
        }
    }

    /** Deletes {@code .ccx/context.json}, and the {@code .ccx/} directory when nothing else is in it. */
    private void deleteContextFile(Path contextFile) {
        try {
            if (!Files.deleteIfExists(contextFile)) {
                return;
            }
            log.debug("context file removed path={}", contextFile);
            var ccxDir = contextFile.getParent();
            try (var entries = Files.list(ccxDir)) {
                if (entries.findAny().isEmpty()) {
                    Files.delete(ccxDir);
                    log.debug(".ccx/ removed path={}", ccxDir);
                }
            }
        } catch (IOException e) {
            log.warn("failed to remove context file path={}", contextFile, e);
        }
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
