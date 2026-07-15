package me.neveux.ccx.cli;

import me.neveux.ccx.service.SettingsService;
import me.neveux.ccx.service.SkillsService;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Callable;

/**
 * Manages Claude Code skill overrides in a project's {@code settings.json}.
 * Skills are identified by a namespaced ID (e.g. {@code java:java}). Overrides
 * control whether a skill is shown to the model automatically ({@code auto}),
 * only on explicit user invocation ({@code user-invocable-only}), or suppressed
 * entirely ({@code off}).
 */
@Command(
    name = "skills",
    description = "Manage skill overrides",
    subcommands = {
        SkillsCommand.ListCommand.class,
        SkillsCommand.HideCommand.class,
        SkillsCommand.EnableCommand.class,
        SkillsCommand.DisableCommand.class,
        SkillsCommand.ShowCommand.class,
        SkillsCommand.ResetCommand.class,
        CommandLine.HelpCommand.class
    },
    mixinStandardHelpOptions = true
)
@Dependent
@Unremovable
@RegisterForReflection
public class SkillsCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    /**
     * Discovers skills from the project settings and prints each one sorted by
     * skill ID, prefixed with a glyph indicating its effective status:
     * {@code auto}, {@code user-invocable-only}, or {@code off}.
     */
    @Command(name = "list", description = "List all discovered skills with their status")
    @RegisterForReflection
    static class ListCommand implements Runnable {

        @Inject
        SkillsService skillsService;

        @Inject
        SettingsService settingsService;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @Option(names = {"--global-dir"}, description = "Global Claude config directory (defaults to ~/.claude)")
        Path globalDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            var projDir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(projDir, spec)) return;
            var globDir = CommandUtil.resolveGlobalDir(globalDir);
            if (!CommandUtil.checkPathSecurity(globDir, spec, "--global-dir")) return;

            var settings = settingsService.read(projDir);
            var skills = skillsService.discoverSkills(settings);

            var out = spec.commandLine().getOut();
            skills.stream()
                .sorted(Comparator.comparing(SkillsService.SkillEntry::skillId))
                .forEach(skill -> {
                    var status = skillsService.getStatus(skill.skillId(), projDir, globDir);
                    var icon = switch (status) {
                        case "auto" -> CliFormat.ENABLED;
                        case "user-invocable-only" -> CliFormat.HIDDEN;
                        case "off" -> CliFormat.OFF;
                        default -> CliFormat.AVAILABLE;
                    };
                    out.println(icon + " " + skill.skillId());
                });
            out.flush();
        }
    }

    /** Sets the named skill's override to {@code user-invocable-only} so it only appears when the user invokes it by name. */
    @Command(name = "hide", description = "Set a skill to user-invocable-only override")
    @RegisterForReflection
    static class HideCommand implements Callable<Integer> {

        @Inject
        SkillsService skillsService;

        @Parameters(index = "0", description = "Skill ID (e.g. java:java)")
        String skillId;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var projDir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(projDir, spec)) return 1;
            skillsService.setOverride(skillId, "user-invocable-only", projDir);
            spec.commandLine().getOut().println("Hidden: " + skillId);
            return 0;
        }
    }

    /** Removes the project-level override for the named skill, restoring its default {@code auto} behaviour. */
    @Command(name = "enable", description = "Enable a skill at auto (remove its override)")
    @RegisterForReflection
    static class EnableCommand implements Callable<Integer> {

        @Inject
        SkillsService skillsService;

        @Parameters(index = "0", description = "Skill ID (e.g. java:java)")
        String skillId;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var projDir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(projDir, spec)) return 1;
            skillsService.removeOverride(skillId, projDir);
            spec.commandLine().getOut().println("Enabled: " + skillId);
            return 0;
        }
    }

    /** Sets the named skill's override to {@code off} so it is never surfaced to the model. */
    @Command(name = "disable", description = "Disable a skill (set off override)")
    @RegisterForReflection
    static class DisableCommand implements Callable<Integer> {

        @Inject
        SkillsService skillsService;

        @Parameters(index = "0", description = "Skill ID (e.g. java:java)")
        String skillId;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var projDir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(projDir, spec)) return 1;
            skillsService.setOverride(skillId, "off", projDir);
            spec.commandLine().getOut().println("Disabled: " + skillId);
            return 0;
        }
    }

    /** Prints the effective override status of a single skill, consulting both project and global settings. */
    @Command(name = "show", description = "Show the current override status of a skill")
    @RegisterForReflection
    static class ShowCommand implements Runnable {

        @Inject
        SkillsService skillsService;

        @Parameters(index = "0", description = "Skill ID (e.g. java:java)")
        String skillId;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @Option(names = {"--global-dir"}, description = "Global Claude config directory (defaults to ~/.claude)")
        Path globalDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            var projDir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(projDir, spec)) return;
            var globDir = CommandUtil.resolveGlobalDir(globalDir);
            if (!CommandUtil.checkPathSecurity(globDir, spec, "--global-dir")) return;
            var status = skillsService.getStatus(skillId, projDir, globDir);
            spec.commandLine().getOut().println(skillId + ": " + status);
        }
    }

    /** Removes all skill overrides from the project's {@code settings.json} and prints each removed entry. */
    @Command(name = "reset", description = "Remove all skill overrides from project settings")
    @RegisterForReflection
    static class ResetCommand implements Callable<Integer> {

        @Inject
        SkillsService skillsService;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var projDir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(projDir, spec)) return 1;
            var removed = skillsService.resetAllOverrides(projDir);

            var out = spec.commandLine().getOut();
            removed.forEach(out::println);
            out.println("Reset " + removed.size() + " override(s).");
            return 0;
        }
    }
}
