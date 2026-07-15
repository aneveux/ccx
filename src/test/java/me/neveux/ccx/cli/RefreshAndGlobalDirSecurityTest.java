package me.neveux.ccx.cli;

import me.neveux.ccx.service.CcxConfigService;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.MarketplaceCloneService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.ProfileService;
import me.neveux.ccx.service.SettingsService;
import me.neveux.ccx.service.SkillsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defense-in-depth path-security guards beyond the project-dir baseline:
 *   - {@code plugins refresh} must reject a --project-dir outside the allowed roots.
 *   - read-only commands must also reject a --global-dir outside the allowed roots
 *     (previously only --project-dir was bounded).
 * /etc lives outside the allowed roots (home, tmp, cwd), so any invocation pointed at
 * /etc must report an "outside allowed" message.
 */
@DisplayName("Path-security guards: refresh project-dir and global-dir bounding")
class RefreshAndGlobalDirSecurityTest {

    private CommandLine commandLine;
    private final ByteArrayOutputStream outputCapture = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        var settingsService = new SettingsService();
        // refresh needs marketplaceCloneService injected; the security check returns
        // before it is used, so a real instance is harmless.
        var factory = new TestCommandFactory(
            settingsService, new ProfileService(), new PluginMapService(),
            new ClaudeMdService(), new SkillsService(settingsService),
            null, null, new CcxConfigService()
        );
        var refreshFactory = new TestCommandFactory(
            settingsService, new ProfileService(), new PluginMapService(),
            new ClaudeMdService(), new MarketplaceCloneService()
        );
        // The refresh command needs MarketplaceCloneService, so prefer the 5-arg
        // factory which wires it; both factories inject the read-command services.
        commandLine = new CommandLine(new CcxCommand(), new CompositeFactory(factory, refreshFactory));
        commandLine.setOut(new PrintWriter(outputCapture, true));
        commandLine.setErr(new PrintWriter(outputCapture, true));
    }

    /**
     * Tries the primary factory first; if it produces a command instance whose required
     * service field is null (i.e. marketplaceCloneService for refresh), the fallback
     * factory (which wires marketplaceCloneService) is used instead.
     */
    private static final class CompositeFactory implements CommandLine.IFactory {
        private final CommandLine.IFactory primary;
        private final CommandLine.IFactory withMarketplace;

        CompositeFactory(CommandLine.IFactory primary, CommandLine.IFactory withMarketplace) {
            this.primary = primary;
            this.withMarketplace = withMarketplace;
        }

        @Override
        public <K> K create(Class<K> cls) throws Exception {
            if (cls == PluginsCommand.RefreshCommand.class) {
                return withMarketplace.create(cls);
            }
            return primary.create(cls);
        }
    }

    static Stream<Arguments> refreshProjectDirOutsideRoots() {
        return Stream.of(
            Arguments.of("plugins refresh", new String[]{"plugins", "refresh", "--project-dir", "/etc"})
        );
    }

    static Stream<Arguments> globalDirOutsideRoots() {
        // project-dir defaults to cwd (allowed); only --global-dir is adversarial.
        return Stream.of(
            Arguments.of("plugins list", new String[]{"plugins", "list", "--global-dir", "/etc"}),
            Arguments.of("plugins diff", new String[]{"plugins", "diff", "--global-dir", "/etc"}),
            Arguments.of("plugins tags", new String[]{"plugins", "tags", "--global-dir", "/etc"}),
            Arguments.of("plugins refresh", new String[]{"plugins", "refresh", "--global-dir", "/etc"}),
            Arguments.of("skills list", new String[]{"skills", "list", "--global-dir", "/etc"}),
            Arguments.of("skills show", new String[]{"skills", "show", "some-skill", "--global-dir", "/etc"}),
            Arguments.of("doctor", new String[]{"doctor", "--global-dir", "/etc"})
        );
    }

    @ParameterizedTest(name = "{0} rejects --project-dir outside allowed roots")
    @MethodSource("refreshProjectDirOutsideRoots")
    @DisplayName("refresh rejects --project-dir outside allowed roots")
    void refreshProjectDirOutsideRoots_isRejected(String label, String[] argv) {
        outputCapture.reset();

        commandLine.execute(argv);

        assertThat(outputCapture.toString())
            .as("%s with --project-dir=/etc must report 'outside allowed'", label)
            .containsIgnoringCase("outside allowed");
    }

    @ParameterizedTest(name = "{0} rejects --global-dir outside allowed roots")
    @MethodSource("globalDirOutsideRoots")
    @DisplayName("read command rejects --global-dir outside allowed roots")
    void globalDirOutsideRoots_isRejected(String label, String[] argv) {
        outputCapture.reset();

        commandLine.execute(argv);

        assertThat(outputCapture.toString())
            .as("%s with --global-dir=/etc must report 'outside allowed'", label)
            .containsIgnoringCase("outside allowed");
    }
}
