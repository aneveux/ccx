package me.neveux.ccx;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import me.neveux.ccx.cli.CcxCommand;
import jakarta.inject.Inject;
import picocli.CommandLine;

/**
 * Quarkus entry point for the ccx CLI.
 *
 * <p>Delegates all argument processing to the Picocli {@link me.neveux.ccx.cli.CcxCommand}
 * root command, using the Quarkus-managed {@link picocli.CommandLine.IFactory} so that CDI
 * beans are injected into subcommands.
 */
@QuarkusMain
public class Main implements QuarkusApplication {

    @Inject
    CommandLine.IFactory factory;

    @Override
    public int run(String... args) {
        return new CommandLine(new CcxCommand(), factory).execute(args);
    }
}
