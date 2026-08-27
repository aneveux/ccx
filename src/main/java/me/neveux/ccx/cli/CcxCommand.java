package me.neveux.ccx.cli;

import me.neveux.ccx.service.ProfileService;
import me.neveux.ccx.service.SettingsService;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Root command for the ccx CLI. When invoked without a subcommand it prints a
 * short banner showing how many plugins are enabled in the current project and
 * hints at next steps. All substantive work is delegated to subcommands.
 */
@Command(
    name = "ccx",
    description = "Claude Code Configuration Manager",
    subcommands = {
        StatusCommand.class,
        DoctorCommand.class,
        CleanCommand.class,
        PluginsCommand.class,
        ProfilesCommand.class,
        SkillsCommand.class,
        McpCommand.class,
        ClaudeMdCommand.class,
        ConfigCommand.class,
        TuiCommand.class,
        CommandLine.HelpCommand.class
    },
    mixinStandardHelpOptions = true,
    versionProvider = CcxCommand.VersionProvider.class
)
public class CcxCommand implements Runnable, CommandLine.IExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CcxCommand.class);

    private int exitCode = 0;

    /** Lets the banner signal a rejected {@code --project-dir}, since {@link Runnable} cannot. */
    @Override
    public int getExitCode() {
        return exitCode;
    }

    @Option(names = {"--project-dir"}, description = "Project directory (defaults to cwd)")
    Path projectDir;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    Supplier<List<String>> availableProfilesSupplier =
        () -> new ProfileService().listAvailableProfiles();

    /**
     * Reads the build version from MicroProfile Config so that {@code ccx --version}
     * always matches the artifact version embedded at build time. Falls back to
     * {@code 0.1.0} when running outside a packaged Quarkus application.
     */
    public static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"ccx " + version()};
        }
    }

    /** Returns the application version string, safe to call outside a Quarkus runtime. */
    public static String version() {
        try {
            return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.application.version", String.class)
                .orElse("0.1.0");
        } catch (Exception e) {
            return "0.1.0";
        }
    }

    @Override
    public void run() {
        var out = spec != null ? spec.commandLine().getOut()
            : new PrintWriter(System.out, true);
        var dir = CommandUtil.resolveProjectDir(projectDir);
        if (!CommandUtil.checkPathSecurity(dir, spec)) {
            exitCode = 1;
            return;
        }

        var pluginCount = enabledPluginCount(dir);
        var noProfiles = hasNoProfiles();

        out.println("  " + CliFormat.teal(CliFormat.BRAND) + " ccx " + CliFormat.dim("· per-project Claude Code config"));
        out.println();
        if (pluginCount == 0) {
            out.println("  " + CliFormat.dim("nothing enabled here yet — create your first profile with `ccx profiles new <name>`"));
            if (noProfiles) {
                out.println();
                out.println("  " + CliFormat.dim("No profiles yet. Create your first with: ccx profiles new <name>"));
            }
        } else {
            out.println("  " + pluginCount + " plugin" + (pluginCount == 1 ? "" : "s") + " enabled here.");
        }
        out.println("  " + CliFormat.dim("Run `ccx tui` to explore, `ccx doctor` to check, `ccx --help` for commands."));
        out.println();
    }

    private int enabledPluginCount(Path dir) {
        try {
            var settings = new SettingsService().read(dir);
            if (settings.enabledPlugins() == null) return 0;
            return (int) settings.enabledPlugins().values().stream()
                .filter(Boolean.TRUE::equals).count();
        } catch (Exception e) {
            log.debug("banner plugin-count read failed dir={}", dir, e);
            return 0;
        }
    }

    private boolean hasNoProfiles() {
        try {
            return availableProfilesSupplier.get().isEmpty();
        } catch (Exception e) {
            log.debug("banner available-profiles read failed", e);
            return false;
        }
    }
}
