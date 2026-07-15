package me.neveux.ccx.cli;

import me.neveux.ccx.service.CcxConfigService;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.tui.Quips;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Manages the {@code <!-- ccx:begin -->} / {@code <!-- ccx:end -->} marker block
 * inside a project's {@code .claude/CLAUDE.md}. The marker block contains a list
 * of {@code @/absolute/path/to/fragment.md} lines that Claude Code reads at
 * startup. Subcommands: list, add, remove, init.
 */
@Command(
    name = "claude-md",
    description = "Manage CLAUDE.md fragment references",
    subcommands = {
        ClaudeMdCommand.ListCommand.class,
        ClaudeMdCommand.AddCommand.class,
        ClaudeMdCommand.RemoveCommand.class,
        ClaudeMdCommand.InitCommand.class,
        CommandLine.HelpCommand.class
    },
    mixinStandardHelpOptions = true
)
@Dependent
@Unremovable
@RegisterForReflection
public class ClaudeMdCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    /**
     * Returns {@code true} when {@code name} is null or contains path-traversal
     * characters ({@code /}, {@code \}, {@code ..}). Used to reject malicious
     * fragment names before they reach the filesystem.
     */
    static boolean isUnsafeName(String name) {
        return name == null || name.contains("/") || name.contains("\\") || name.contains("..");
    }

    /** Prints the {@code @path} lines currently inside the ccx marker block, one per line. */
    @Command(name = "list", description = "List configured fragments")
    @RegisterForReflection
    static class ListCommand implements Callable<Integer> {

        @Inject
        ClaudeMdService claudeMdService;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var dir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(dir, spec)) return 1;
            var fragments = claudeMdService.readMarkerBlock(dir.resolve(".claude/CLAUDE.md"));

            if (fragments.isEmpty()) {
                spec.commandLine().getOut().println(
                    Quips.EMPTY[0] + " no fragments configured");
                return 0;
            }

            var out = spec.commandLine().getOut();
            fragments.forEach(out::println);
            return 0;
        }
    }

    /**
     * Appends a global claudemd fragment reference to the ccx marker block.
     * The fragment must already exist under the global claudemd directory
     * (create it with {@code ccx config claudemd new}). Does nothing if the
     * reference is already present.
     */
    @Command(name = "add", description = "Add a global claudemd fragment to .claude/CLAUDE.md")
    @RegisterForReflection
    static class AddCommand implements Callable<Integer> {

        @Inject
        CcxConfigService ccxConfigService;

        @Inject
        ClaudeMdService claudeMdService;

        @Parameters(index = "0", description = "Fragment name")
        String name;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            if (isUnsafeName(name)) {
                spec.commandLine().getErr().println("Error: fragment name contains invalid characters: " + name);
                return 1;
            }
            var dir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(dir, spec)) return 1;

            var fragFile = ccxConfigService.globalClaudeMdDir().resolve(name + ".md");
            if (!Files.exists(fragFile)) {
                spec.commandLine().getErr().println("Fragment '" + name + "' not found. Use `ccx config claudemd new` to create it.");
                return 1;
            }

            var claudeMd = dir.resolve(".claude/CLAUDE.md");
            var absPath = fragFile.toAbsolutePath().normalize().toString();
            var fragLine = "@" + absPath;
            var lines = claudeMdService.readMarkerBlock(claudeMd);

            if (lines.contains(fragLine)) {
                spec.commandLine().getOut().println("Fragment already present: " + fragLine);
                return 0;
            }

            var newList = new ArrayList<>(lines);
            newList.add(fragLine);
            claudeMdService.writeMarkerBlock(claudeMd, newList);
            spec.commandLine().getOut().println("Added: " + fragLine);
            return 0;
        }
    }

    /** Removes a fragment reference from the ccx marker block. Reports success even if the fragment is not present. */
    @Command(name = "remove", description = "Remove a global claudemd fragment from .claude/CLAUDE.md")
    @RegisterForReflection
    static class RemoveCommand implements Callable<Integer> {

        @Inject
        CcxConfigService ccxConfigService;

        @Inject
        ClaudeMdService claudeMdService;

        @Parameters(index = "0", description = "Fragment name")
        String name;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            if (isUnsafeName(name)) {
                spec.commandLine().getErr().println("Error: fragment name contains invalid characters: " + name);
                return 1;
            }
            var dir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(dir, spec)) return 1;

            var fragFile = ccxConfigService.globalClaudeMdDir().resolve(name + ".md");
            var absPath = fragFile.toAbsolutePath().normalize().toString();
            var fragLine = "@" + absPath;

            var claudeMd = dir.resolve(".claude/CLAUDE.md");
            var lines = claudeMdService.readMarkerBlock(claudeMd);

            if (!lines.contains(fragLine)) {
                spec.commandLine().getOut().println("Fragment not found: " + fragLine);
                return 0;
            }

            var newList = new ArrayList<>(lines);
            newList.remove(fragLine);
            claudeMdService.writeMarkerBlock(claudeMd, newList);
            spec.commandLine().getOut().println("Removed: " + fragLine);
            return 0;
        }
    }

    /**
     * Creates {@code .claude/CLAUDE.md} with an empty ccx marker block when the
     * file does not yet exist. Is a no-op if the file is already present.
     */
    @Command(name = "init", description = "Initialize .claude/CLAUDE.md with an empty marker block")
    @RegisterForReflection
    static class InitCommand implements Callable<Integer> {

        @Inject
        ClaudeMdService claudeMdService;

        @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
        Path projectDir;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var dir = CommandUtil.resolveProjectDir(projectDir);
            if (!CommandUtil.checkPathSecurity(dir, spec)) return 1;
            var claudeMd = dir.resolve(".claude/CLAUDE.md");

            if (Files.exists(claudeMd)) {
                spec.commandLine().getOut().println(".claude/CLAUDE.md already exists");
                return 0;
            }

            claudeMdService.writeMarkerBlock(claudeMd, List.of());
            spec.commandLine().getOut().println("Initialized .claude/CLAUDE.md");
            return 0;
        }
    }
}
