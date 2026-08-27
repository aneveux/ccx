package me.neveux.ccx;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import me.neveux.ccx.cli.CcxCommand;
import me.neveux.ccx.cli.CliFormat;
import me.neveux.ccx.service.ConfigReadException;
import me.neveux.ccx.service.ConfigWriteException;
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
        var commandLine = new CommandLine(new CcxCommand(), factory);
        commandLine.setExecutionExceptionHandler(Main::reportConfigFailure);
        return commandLine.execute(args);
    }

    /**
     * Renders a refused config read or a failed config write as one actionable line — both mean
     * "your file is fine, ccx stopped" and a stack trace only hides that. Every other exception
     * keeps picocli's default handling.
     */
    private static int reportConfigFailure(Exception exception, CommandLine commandLine,
                                           CommandLine.ParseResult parseResult) throws Exception {
        if (!(exception instanceof ConfigReadException) && !(exception instanceof ConfigWriteException)) {
            throw exception;
        }
        var err = commandLine.getErr();
        err.println(CliFormat.ERR + " " + exception.getMessage());
        err.flush();
        return commandLine.getCommandSpec().exitCodeOnExecutionException();
    }
}
