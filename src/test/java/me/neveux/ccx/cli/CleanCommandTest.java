// Wave 3 — Commands
package me.neveux.ccx.cli;

import me.neveux.ccx.model.CcxContext;
import me.neveux.ccx.model.Permissions;
import me.neveux.ccx.model.Settings;
import me.neveux.ccx.service.CcxContextService;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.ProfileService;
import me.neveux.ccx.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CleanCommand")
class CleanCommandTest {

    @TempDir
    Path tempDir;

    private SettingsService settingsService;
    private ClaudeMdService claudeMdService;
    private CommandLine commandLine;

    @BeforeEach
    void setUp() {
        settingsService = new SettingsService();
        claudeMdService = new ClaudeMdService();

        var factory = new TestCommandFactory(
            settingsService, new ProfileService(), new PluginMapService(),
            claudeMdService
        );
        commandLine = new CommandLine(new CcxCommand(), factory);
    }

    private RunResult run(String... args) {
        var out = new ByteArrayOutputStream();
        commandLine.setOut(new PrintWriter(out, true));
        var exit = commandLine.execute(args);
        return new RunResult(exit, out.toString());
    }

    @Test
    @DisplayName("should remove marker block while preserving content before and after it")
    void cleanForce_removesMarkerBlockLeavingContentOutside() throws Exception {
        var claudeMd = tempDir.resolve(".claude/CLAUDE.md");
        Files.createDirectories(claudeMd.getParent());
        Files.writeString(claudeMd, """
            # My Project

            Content before.

            <!-- ccx:begin -->
            @~/.claude/stacks/java.md
            <!-- ccx:end -->

            Content after.
            """);

        var settings = new Settings().withEnabledPlugins(Map.of("java@claude-java-stack", true));
        settingsService.write(settings, tempDir);

        var result = run("clean", "--force", "--project-dir", tempDir.toString());

        assertThat(result.exitCode()).isZero();

        var content = Files.readString(claudeMd);
        assertThat(content)
            .as("content before ccx block must be preserved after clean")
            .contains("Content before.");
        assertThat(content)
            .as("content after ccx block must be preserved after clean")
            .contains("Content after.");
        assertThat(content)
            .as("ccx block must be removed")
            .doesNotContain("<!-- ccx:begin -->");
    }

    @Test
    @DisplayName("should empty enabledPlugins in settings after --force clean")
    void cleanForce_clearsEnabledPlugins() throws Exception {
        var settings = new Settings().withEnabledPlugins(Map.of("java@claude-java-stack", true));
        settingsService.write(settings, tempDir);

        run("clean", "--force", "--project-dir", tempDir.toString());

        var restored = settingsService.read(tempDir);
        assertThat(restored.enabledPlugins())
            .as("enabledPlugins must be empty after clean --force")
            .isNullOrEmpty();
    }

    @Test
    @DisplayName("should print what would be removed without modifying files when no TTY")
    void cleanWithoutForceAndNoTty_listsItemsWithoutModifying() throws Exception {
        var claudeMd = tempDir.resolve(".claude/CLAUDE.md");
        Files.createDirectories(claudeMd.getParent());
        Files.writeString(claudeMd, """
            <!-- ccx:begin -->
            @~/.claude/stacks/java.md
            <!-- ccx:end -->
            """);

        var settings = new Settings().withEnabledPlugins(Map.of("java@claude-java-stack", true));
        settingsService.write(settings, tempDir);

        var contentBefore = Files.readString(claudeMd);
        var settingsBefore = Files.readString(tempDir.resolve(".claude/settings.json"));

        // Run clean without --force and with no TTY (piped stdin)
        var result = run("clean", "--project-dir", tempDir.toString());

        assertThat(result.exitCode())
            .as("clean without --force and no TTY must exit 0")
            .isZero();
        assertThat(result.output())
            .as("clean must print what would be removed")
            .isNotBlank()
            .contains("java@claude-java-stack");
        assertThat(Files.readString(claudeMd))
            .as("CLAUDE.md must not be modified without --force")
            .isEqualTo(contentBefore);
        assertThat(Files.readString(tempDir.resolve(".claude/settings.json")))
            .as("settings.json must not be modified without --force")
            .isEqualTo(settingsBefore);
    }

    @Test
    @DisplayName("should remove .claudeignore when it exists and --force is given")
    void cleanForce_removesClaudeIgnore_whenItExists() throws Exception {
        var claudeIgnore = tempDir.resolve(".claudeignore");
        Files.writeString(claudeIgnore, ".git/\ntarget/\n");

        run("clean", "--force", "--project-dir", tempDir.toString());

        assertThat(claudeIgnore)
            .as(".claudeignore must be deleted by clean --force")
            .doesNotExist();
    }

    @Test
    @DisplayName("should remove .claude/rules/ directory when it exists and --force is given")
    void cleanForce_removesRulesDir_whenItExists() throws Exception {
        var rulesDir = tempDir.resolve(".claude/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("java.md"), "# java rules");
        Files.writeString(rulesDir.resolve("git.md"), "# git rules");

        run("clean", "--force", "--project-dir", tempDir.toString());

        assertThat(rulesDir)
            .as(".claude/rules/ must be deleted by clean --force")
            .doesNotExist();
    }

    @Test
    @DisplayName("should list .claudeignore and .claude/rules/ in dry-run output")
    void cleanWithoutForce_listsDotClaudeIgnoreAndRules_inDryRunOutput() throws Exception {
        Files.writeString(tempDir.resolve(".claudeignore"), ".git/\n");
        var rulesDir = tempDir.resolve(".claude/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("java.md"), "# rule");

        var result = run("clean", "--project-dir", tempDir.toString());

        assertThat(result.output())
            .as("dry-run output must mention .claudeignore")
            .contains(".claudeignore");
        assertThat(result.output())
            .as("dry-run output must mention .claude/rules/")
            .contains(".claude/rules/");
    }

    @Test
    @DisplayName("should exit 0 and print a satisfying tidy sign-off even when .ccx/context.json exists")
    void cleanForce_doesNotFailWhenCcxContextExists() throws Exception {
        var ccxDir = tempDir.resolve(".ccx");
        Files.createDirectories(ccxDir);
        Files.writeString(ccxDir.resolve("context.json"), "{\"enabledProfiles\":{}}");

        var result = run("clean", "--force", "--project-dir", tempDir.toString());

        assertThat(result.exitCode())
            .as("clean --force must exit 0 even when .ccx/context.json is present")
            .isZero();
        assertThat(result.output())
            .as("clean --force must print the tidy sign-off (Wave 5 voice)")
            .contains("tidied")
            .contains("vanilla");
    }

    // -----------------------------------------------------------------------
    // recorded profiles are reverted, not stranded
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when profiles are recorded in .ccx/context.json")
    class WhenProfilesAreRecorded {

        private void recordProfile() throws Exception {
            var settings = new Settings()
                .withEnabledPlugins(Map.of("java@my-mkt", true))
                .withEnv(Map.of("MAVEN_OPTS", "-Xmx2g"))
                .withPermissions(new Permissions(List.of("Bash(mvn:*)"), null));
            settingsService.write(settings, tempDir);

            var ruleFile = tempDir.resolve(".claude/rules/java.md");
            Files.createDirectories(ruleFile.getParent());
            Files.writeString(ruleFile, "# rules\n");

            var snapshot = new CcxContext.ProfileSnapshot(
                List.of("java@my-mkt"), List.of("java.md"),
                List.of("Bash(mvn:*)"), List.of(),
                List.of(), List.of(),
                Map.of("MAVEN_OPTS", "-Xmx2g"), List.of());
            new CcxContextService().recordEnabled("java", snapshot, tempDir);
        }

        @Test
        @DisplayName("clean --force reverts the recorded profile's permissions, env and rule files")
        void cleanForce_revertsRecordedProfile() throws Exception {
            recordProfile();

            var result = run("clean", "--force", "--project-dir", tempDir.toString());

            assertThat(result.exitCode()).isZero();
            var restored = settingsService.read(tempDir);
            assertThat(restored.permissions() == null ? List.<String>of() : restored.permissions().allow())
                .as("a permission ccx granted through the profile must go with it, not be stranded")
                .isNullOrEmpty();
            assertThat(restored.env())
                .as("an env entry ccx granted through the profile must go with it")
                .isNullOrEmpty();
            assertThat(tempDir.resolve(".claude/rules/java.md"))
                .as("the profile's rule file must be removed")
                .doesNotExist();
        }

        @Test
        @DisplayName("clean --force removes .ccx/context.json and the now-empty .ccx/ directory")
        void cleanForce_removesContextFileAndEmptyDirectory() throws Exception {
            recordProfile();
            assertThat(tempDir.resolve(".ccx/context.json")).exists();

            run("clean", "--force", "--project-dir", tempDir.toString());

            assertThat(tempDir.resolve(".ccx/context.json"))
                .as("the context file records what ccx owns — clean must drop it")
                .doesNotExist();
            assertThat(tempDir.resolve(".ccx"))
                .as("an empty .ccx/ left behind is litter")
                .doesNotExist();
        }

        @Test
        @DisplayName("clean without --force names the profiles it would revert and changes nothing")
        void cleanWithoutForce_listsProfilesToRevert() throws Exception {
            recordProfile();
            var contextBefore = Files.readString(tempDir.resolve(".ccx/context.json"));

            var result = run("clean", "--project-dir", tempDir.toString());

            assertThat(result.exitCode()).isZero();
            assertThat(result.output())
                .as("a dry run must name the profiles whose revert is the destructive part")
                .contains("java");
            assertThat(Files.readString(tempDir.resolve(".ccx/context.json")))
                .as("a dry run must not touch the context file")
                .isEqualTo(contextBefore);
            assertThat(tempDir.resolve(".claude/rules/java.md"))
                .as("a dry run must not delete rule files")
                .exists();
        }
    }

    @Nested
    @DisplayName("when settings.json is corrupt")
    class WhenSettingsJsonIsCorrupt {

        private static final String CORRUPT = "{\"model\":\"claude-opus-4-8\",\"enabledPlugins\":{\"java@my-mkt\":true}";

        @Test
        @DisplayName("clean --force refuses before removing anything and leaves the files as they were")
        void cleanForce_corruptSettings_refusesAndChangesNothing() throws Exception {
            var settingsFile = tempDir.resolve(".claude/settings.json");
            Files.createDirectories(settingsFile.getParent());
            Files.writeString(settingsFile, CORRUPT);
            var claudeMd = tempDir.resolve(".claude/CLAUDE.md");
            var claudeMdContent = "# My Project\n\n<!-- ccx:begin -->\n@~/.claude/stacks/java.md\n<!-- ccx:end -->\n";
            Files.writeString(claudeMd, claudeMdContent);

            var out = new ByteArrayOutputStream();
            var err = new ByteArrayOutputStream();
            commandLine.setOut(new PrintWriter(out, true));
            commandLine.setErr(new PrintWriter(err, true));
            var exit = commandLine.execute("clean", "--force", "--project-dir", tempDir.toString());

            assertThat(exit)
                .as("a clean that cannot read settings.json must fail, not proceed")
                .isNotZero();
            assertThat(err.toString())
                .as("the user has to know which file to repair")
                .contains("Refusing to modify")
                .contains(settingsFile.toString());
            assertThat(settingsFile)
                .as("the corrupt file must survive for the user to fix")
                .hasContent(CORRUPT);
            assertThat(claudeMd)
                .as("nothing may be removed before the settings read succeeds — a half-clean is worse than none")
                .hasContent(claudeMdContent);
        }
    }

    // -----------------------------------------------------------------------
    // Plan 041 W4C — ConfigWriteException propagation
    // -----------------------------------------------------------------------

    static class FailingSettingsService extends SettingsService {
        @Override
        public void write(me.neveux.ccx.model.Settings settings, java.nio.file.Path projectDir) {
            throw new me.neveux.ccx.service.ConfigWriteException("simulated disk full", null);
        }
    }

    @Nested
    @DisplayName("when settings write fails")
    class WhenSettingsWriteFails {

        @Test
        @DisplayName("clean --force exits 1 with error output on ConfigWriteException")
        void clean_force_settingsWriteFails_exits1() throws Exception {
            var claudeDir = tempDir.resolve(".claude");
            Files.createDirectories(claudeDir);
            Files.writeString(claudeDir.resolve("settings.json"), "{}");

            var factory = new TestCommandFactory(
                new FailingSettingsService(), new ProfileService(), new PluginMapService(),
                new ClaudeMdService()
            );
            var out = new ByteArrayOutputStream();
            var err = new ByteArrayOutputStream();
            var cmd = new CommandLine(new CcxCommand(), factory);
            cmd.setOut(new PrintWriter(out, true));
            cmd.setErr(new PrintWriter(err, true));

            var exit = cmd.execute("clean", "--project-dir", tempDir.toString(), "--force");

            assertThat(exit).as("exit code must be 1 on ConfigWriteException").isEqualTo(1);
            assertThat(err.toString())
                .as("error output must mention the failure")
                .contains("simulated disk full");
        }
    }

    // -----------------------------------------------------------------------
    // Plan 040 Item 13 — no-arg constructor initialises fields that are private final
    // RED until the no-arg constructor initialises settingsService/claudeMdService
    // AND the fields are declared final
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("no-arg constructor must initialise settingsService and declare it private final")
    void noArgConstructor_fieldsNotNull() throws Exception {
        var cmd = new CleanCommand();

        var settingsField = findField(CleanCommand.class, "settingsService");
        assertThat(settingsField)
            .as("CleanCommand must have a 'settingsService' field")
            .isNotNull();

        settingsField.setAccessible(true);
        assertThat(settingsField.get(cmd))
            .as("no-arg constructor must initialise settingsService to a non-null value")
            .isNotNull();

        assertThat(Modifier.isPrivate(settingsField.getModifiers()))
            .as("settingsService must be private")
            .isTrue();
        assertThat(Modifier.isFinal(settingsField.getModifiers()))
            .as("settingsService must be final")
            .isTrue();
    }

    private static java.lang.reflect.Field findField(Class<?> cls, String name) {
        while (cls != null) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    private record RunResult(int exitCode, String output) {}
}
