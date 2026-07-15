package me.neveux.ccx.cli;

import me.neveux.ccx.service.CcxConfigService;
import me.neveux.ccx.util.FileUtils;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.Callable;

/**
 * Entry point for managing ccx's own global configuration (i.e. files stored
 * under the user-level ccx config directory, not a specific project). Currently
 * provides the {@code claudemd} sub-tree for creating reusable CLAUDE.md fragments.
 */
@Command(name = "config", description = "Manage ccx configuration",
    subcommands = { ConfigCommand.ClaudeMdConfigCommand.class, CommandLine.HelpCommand.class },
    mixinStandardHelpOptions = true)
@Dependent
@Unremovable
@RegisterForReflection
public class ConfigCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    /** Namespace for managing the user-level global claudemd fragment library. */
    @Command(name = "claudemd", description = "Manage global claudemd fragments",
        subcommands = { ClaudeMdConfigCommand.NewCommand.class, CommandLine.HelpCommand.class },
        mixinStandardHelpOptions = true)
    @RegisterForReflection
    static class ClaudeMdConfigCommand implements Runnable {

        @Override
        public void run() {
            // no-op: shows help when no subcommand given
        }

        /**
         * Scaffolds a new Markdown file in the global claudemd directory. The file
         * name is derived from the {@code name} parameter. Fails if a fragment with
         * the same name already exists, or if the name does not match
         * {@code [a-z0-9-]+}.
         */
        @Command(name = "new", description = "Create a new global claudemd fragment")
        @RegisterForReflection
        static class NewCommand implements Callable<Integer> {

            @Inject
            CcxConfigService ccxConfigService;

            @Parameters(index = "0", description = "Fragment name (lowercase letters, digits, hyphens)")
            String name;

            @CommandLine.Spec
            CommandLine.Model.CommandSpec spec;

            @Override
            public Integer call() {
                if (!name.matches(CommandUtil.VALID_NAME_PATTERN)) {
                    spec.commandLine().getErr().println("Error: invalid fragment name '" + name + "': only lowercase letters, digits, and hyphens allowed");
                    return 1;
                }
                var fragFile = ccxConfigService.globalClaudeMdDir().resolve(name + ".md");
                if (Files.exists(fragFile)) {
                    spec.commandLine().getErr().println("Error: fragment '" + name + "' already exists at " + fragFile.toAbsolutePath());
                    return 1;
                }
                try {
                    FileUtils.atomicWrite(fragFile, "# " + name + "\n\nAdd your CLAUDE.md fragment content here.\n");
                    spec.commandLine().getOut().println("Created: " + fragFile.toAbsolutePath());
                    return 0;
                } catch (IOException e) {
                    spec.commandLine().getErr().println("Error creating fragment '" + name + "': " + e.getMessage());
                    return 1;
                }
            }
        }
    }
}
