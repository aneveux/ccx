// Wave 4 — Stubs + Failing Tests
package me.neveux.ccx.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import me.neveux.ccx.service.CcxConfigService;
import me.neveux.ccx.service.CcxContextService;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.ProfileApplyService;
import me.neveux.ccx.service.ProfileService;
import me.neveux.ccx.service.SettingsService;
import me.neveux.ccx.service.SkillsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProfilesCommand")
class ProfilesCommandTest {

    @TempDir
    Path ccxConfigDir;

    private CommandLine commandLine;
    private final ByteArrayOutputStream outputCapture = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws Exception {
        writeTestProfiles(ccxConfigDir);
        var factory = new TestCommandFactory(
            new SettingsService(), buildProfileService(), new PluginMapService(),
            new ClaudeMdService()
        );
        commandLine = new CommandLine(new CcxCommand(), factory);
        commandLine.setOut(new PrintWriter(outputCapture, true));
        commandLine.setErr(new PrintWriter(outputCapture, true));
    }

    private ProfileService buildProfileService() {
        var yamlMapper = new ObjectMapper(new YAMLFactory());
        yamlMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        yamlMapper.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
        return new ProfileService(yamlMapper, new CcxConfigService(ccxConfigDir));
    }

    private void writeTestProfiles(Path configRoot) throws Exception {
        for (var name : new String[]{"java", "bash", "docs", "frontend", "go", "meta"}) {
            var dir = configRoot.resolve("profiles/" + name);
            Files.createDirectories(dir);
            var yaml = "metadata:\n  name: " + name + "\n  description: " + name + " profile\n"
                + "plugins:\n  enable:\n    - " + name + "@claude-java-stack\n"
                + "rules:\n  files:\n    - " + name + "\n";
            Files.writeString(dir.resolve("profile.yaml"), yaml);
        }
    }

    private int run(String... args) {
        outputCapture.reset();
        return commandLine.execute(args);
    }

    private String output() {
        return outputCapture.toString();
    }

    /** Builds a CommandLine wired with ProfileApplyService + CcxContextService for the mutating subcommand tests. */
    private CommandLine buildFullCommandLine(ByteArrayOutputStream capture) {
        var settingsService = new SettingsService();
        var factory = new TestCommandFactory(
            settingsService,
            buildProfileService(),
            new PluginMapService(),
            new ClaudeMdService(),
            new SkillsService(settingsService),
            new ProfileApplyService(),
            new CcxContextService()
        );
        var cl = new CommandLine(new CcxCommand(), factory);
        cl.setOut(new PrintWriter(capture, true));
        cl.setErr(new PrintWriter(capture, true));
        return cl;
    }

    @Nested
    @DisplayName("profiles list")
    class List_ {

        @Test
        @DisplayName("should print all six built-in profile names, one per line")
        void list_printsAllBuiltInProfiles() {
            run("profiles", "list");

            assertThat(output())
                .as("profiles list must print all 6 built-in profile names")
                .contains("bash")
                .contains("docs")
                .contains("frontend")
                .contains("go")
                .contains("java")
                .contains("meta");
        }
    }

    @Nested
    @DisplayName("profiles show")
    class Show {

        @Test
        @DisplayName("should print plugins for the java profile")
        void show_printsPlugins() {
            run("profiles", "show", "java");

            assertThat(output())
                .as("profiles show java must include a java@ prefixed plugin")
                .contains("java@");
        }

        @Test
        @DisplayName("should exit non-zero when the profile name does not exist")
        void show_exitsNonZeroForUnknownProfile() {
            int exit = run("profiles", "show", "nonexistent-profile-xyz");

            assertThat(exit)
                .as("profiles show with unknown profile name must exit non-zero")
                .isNotZero();
            assertThat(output())
                .as("profiles show with unknown profile name must report the profile was not found")
                .containsIgnoringCase("not found");
        }
    }

    @Nested
    @DisplayName("profiles show — completeness (rules, env, ignore, permissions)")
    class ShowDetails {

        @TempDir
        Path richConfigDir;

        private ByteArrayOutputStream capture;
        private CommandLine cl;

        @BeforeEach
        void setUpRichProfile() throws Exception {
            // Write a richer java profile that includes env, ignore, rules, and permissions
            var dir = richConfigDir.resolve("profiles/java");
            Files.createDirectories(dir.resolve("rules"));
            Files.writeString(dir.resolve("rules/java.md"), "# Java rules\n");
            var yaml = "metadata:\n  name: java\n  description: Java profile\n"
                + "plugins:\n  enable:\n    - java@claude-java-stack\n"
                + "rules:\n  files:\n    - java\n"
                + "env:\n  TEST_VAR: \"1\"\n"
                + "ignore:\n  - \"target/\"\n"
                + "permissions:\n  allow:\n    - \"Bash(mvn *)\"\n";
            Files.writeString(dir.resolve("profile.yaml"), yaml);

            capture = new ByteArrayOutputStream();
            var yamlMapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
            yamlMapper.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            yamlMapper.enable(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
            var settingsService = new me.neveux.ccx.service.SettingsService();
            var ccxConfig = new CcxConfigService(richConfigDir);
            var profileService = new me.neveux.ccx.service.ProfileService(yamlMapper, ccxConfig);
            var factory = new TestCommandFactory(
                settingsService, profileService,
                new me.neveux.ccx.service.PluginMapService(),
                new me.neveux.ccx.service.ClaudeMdService(),
                new me.neveux.ccx.service.SkillsService(),
                new me.neveux.ccx.service.ProfileApplyService(),
                new me.neveux.ccx.service.CcxContextService(),
                ccxConfig
            );
            cl = new CommandLine(new CcxCommand(), factory);
            cl.setOut(new PrintWriter(capture, true));
            cl.setErr(new PrintWriter(capture, true));
        }

        private int runShow(String... args) {
            capture.reset();
            return cl.execute(args);
        }

        private String capturedOutput() {
            return capture.toString();
        }

        @Test
        @DisplayName("should include Rules: section in output for a profile that has rules")
        void show_includesRulesSectionInOutput() {
            runShow("profiles", "show", "java");

            assertThat(capturedOutput())
                .as("profiles show output must include a 'Rules:' section header")
                .contains("Rules:");
        }

        @Test
        @DisplayName("should include Env: section in output for a profile that has env vars")
        void show_includesEnvSectionInOutput() {
            runShow("profiles", "show", "java");

            assertThat(capturedOutput())
                .as("profiles show output must include an 'Env:' section header")
                .contains("Env:");
        }

        @Test
        @DisplayName("should include Ignore: section in output for a profile that has ignore patterns")
        void show_includesIgnoreSectionInOutput() {
            runShow("profiles", "show", "java");

            assertThat(capturedOutput())
                .as("profiles show output must include an 'Ignore:' section header")
                .contains("Ignore:");
        }

        @Test
        @DisplayName("should output exactly one Claudemd entry and no legacy Fragments entry when claudemd dir has files")
        void show_noClaudeMdDuplication_onlyClaudemdShown() throws Exception {
            // Add a claudemd/ subdirectory with one .md file to the java profile
            var claudeMdDir = richConfigDir.resolve("profiles/java/claudemd");
            Files.createDirectories(claudeMdDir);
            Files.writeString(claudeMdDir.resolve("java.md"), "# Java claudemd\n");

            // Build a CommandLine that has CcxConfigService wired (needed for Fragments/ClaudeMd blocks)
            var captureWithConfig = new ByteArrayOutputStream();
            var yamlMapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
            yamlMapper.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            yamlMapper.enable(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
            var settingsService = new me.neveux.ccx.service.SettingsService();
            var ccxConfig = new CcxConfigService(richConfigDir);
            var profileService = new me.neveux.ccx.service.ProfileService(yamlMapper, ccxConfig);
            var factory = new TestCommandFactory(
                settingsService, profileService,
                new me.neveux.ccx.service.PluginMapService(),
                new me.neveux.ccx.service.ClaudeMdService(),
                new me.neveux.ccx.service.SkillsService(settingsService),
                null, null,
                ccxConfig
            );
            var clWithConfig = new CommandLine(new CcxCommand(), factory);
            clWithConfig.setOut(new PrintWriter(captureWithConfig, true));
            clWithConfig.setErr(new PrintWriter(captureWithConfig, true));

            clWithConfig.execute("profiles", "show", "java");
            var out = captureWithConfig.toString();

            assertThat(out)
                .as("output must contain 'Claudemd:' for the claudemd directory")
                .contains("Claudemd:");
            assertThat(countOccurrences(out, "Claudemd:"))
                .as("'Claudemd:' must appear exactly once — not duplicated")
                .isEqualTo(1);
            assertThat(out)
                .as("output must not contain 'Fragments:' — label was renamed to Claudemd:")
                .doesNotContain("Fragments:");
        }

        private long countOccurrences(String text, String substring) {
            long count = 0;
            int idx = 0;
            while ((idx = text.indexOf(substring, idx)) != -1) {
                count++;
                idx += substring.length();
            }
            return count;
        }
    }

    @Nested
    @DisplayName("profiles enable")
    class Enable {

        @TempDir
        Path tempDir;

        private ByteArrayOutputStream capture;
        private CommandLine cl;

        @BeforeEach
        void setUpFull() {
            capture = new ByteArrayOutputStream();
            cl = buildFullCommandLine(capture);
        }

        private int runFull(String... args) {
            capture.reset();
            return cl.execute(args);
        }

        private String capturedOutput() {
            return capture.toString();
        }

        @Test
        @DisplayName("should exit 0, confirm the action, and write the java plugin to settings.json")
        void enable_exitsZeroAndOutputsSomething() throws Exception {
            int exit = runFull("profiles", "enable", "java", "--project-dir", tempDir.toString());

            assertThat(exit)
                .as("profiles enable java must exit 0")
                .isZero();
            assertThat(capturedOutput())
                .as("profiles enable must print a confirmation message")
                .contains("Enabled profile: java");

            var settingsContent = Files.readString(tempDir.resolve(".claude/settings.json"));
            assertThat(settingsContent)
                .as("settings.json must contain the java plugin after enable")
                .contains("java");
        }

        @Test
        @DisplayName("should create .ccx/context.json recording the enabled profile")
        void enable_createsContextJsonInProjectDir() throws Exception {
            runFull("profiles", "enable", "java", "--project-dir", tempDir.toString());

            var contextJson = tempDir.resolve(".ccx/context.json");
            assertThat(contextJson)
                .as(".ccx/context.json must be created after profiles enable java")
                .exists();
            var contextContent = Files.readString(contextJson);
            assertThat(contextContent)
                .as(".ccx/context.json must record the enabled profile name")
                .contains("\"java\"");
        }

        @Test
        @DisplayName("should exit 1 when the profile name does not exist")
        void enable_exitsNonZeroForUnknownProfile() {
            int exit = runFull("profiles", "enable", "unknown-profile-xyz", "--project-dir", tempDir.toString());

            assertThat(exit)
                .as("profiles enable with unknown profile must exit non-zero")
                .isNotZero();
            assertThat(capturedOutput())
                .as("profiles enable with unknown profile must report the profile was not found")
                .containsIgnoringCase("not found");
        }

        @Test
        @DisplayName("should auto-enable a profile's transitive dependencies (Wave 6A)")
        void enable_dependent_recordsDependencyEnabledToo() throws Exception {
            // java-functional extends java; java already exists from writeTestProfiles
            var dir = ccxConfigDir.resolve("profiles/java-functional");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("profile.yaml"),
                "metadata:\n  name: java-functional\nextends:\n  - java\n"
                + "plugins:\n  enable:\n    - java-functional@claude-java-stack\n");

            int exit = runFull("profiles", "enable", "java-functional", "--project-dir", tempDir.toString());

            assertThat(exit)
                .as("enabling a dependent profile must exit 0")
                .isZero();
            var contextContent = Files.readString(tempDir.resolve(".ccx/context.json"));
            assertThat(contextContent)
                .as("enabling java-functional must record its dependency java as enabled too")
                .contains("\"java\"")
                .contains("\"java-functional\"");
        }
    }

    @Nested
    @DisplayName("profiles disable")
    class Disable {

        @TempDir
        Path tempDir;

        private ByteArrayOutputStream capture;
        private CommandLine cl;

        @BeforeEach
        void setUpFull() {
            capture = new ByteArrayOutputStream();
            cl = buildFullCommandLine(capture);
        }

        private int runFull(String... args) {
            capture.reset();
            return cl.execute(args);
        }

        private String capturedOutput() {
            return capture.toString();
        }

        @Test
        @DisplayName("should exit 0 and confirm the action after the profile has been enabled")
        void disable_exitsZeroAfterEnable() {
            runFull("profiles", "enable", "java", "--project-dir", tempDir.toString());

            int exit = runFull("profiles", "disable", "java", "--project-dir", tempDir.toString());

            assertThat(exit)
                .as("profiles disable java must exit 0 when the profile was enabled")
                .isZero();
            assertThat(capturedOutput())
                .as("profiles disable must print a confirmation message")
                .contains("Disabled profile: java");
        }

        @Test
        @DisplayName("should exit 1 with a message when the profile is not currently enabled")
        void disable_exitsNonZeroWhenProfileNotEnabled() {
            int exit = runFull("profiles", "disable", "java", "--project-dir", tempDir.toString());

            assertThat(exit)
                .as("profiles disable on a non-enabled profile must exit non-zero")
                .isNotZero();
            assertThat(capturedOutput())
                .as("profiles disable on a non-enabled profile must report it is not enabled")
                .containsIgnoringCase("is not enabled");
        }
    }

    @Nested
    @DisplayName("profiles reinstall")
    class Reinstall {

        @TempDir
        Path tempDir;

        private ByteArrayOutputStream capture;
        private CommandLine cl;

        @BeforeEach
        void setUpFull() {
            capture = new ByteArrayOutputStream();
            cl = buildFullCommandLine(capture);
            // Pre-enable so reinstall has something to work with
            cl.execute("profiles", "enable", "java", "--project-dir", tempDir.toString());
            capture.reset();
        }

        private int runFull(String... args) {
            capture.reset();
            return cl.execute(args);
        }

        private String capturedOutput() {
            return capture.toString();
        }

        @Test
        @DisplayName("should exit 0 and confirm the reinstall action")
        void reinstall_exitsZeroAndOutputsSomething() {
            int exit = runFull("profiles", "reinstall", "java", "--project-dir", tempDir.toString());

            assertThat(exit)
                .as("profiles reinstall java must exit 0")
                .isZero();
            assertThat(capturedOutput())
                .as("profiles reinstall must print a confirmation message")
                .contains("Reinstalled profile: java");
        }

        @Test
        @DisplayName("should exit 1 and report error when the profile name does not exist")
        void reinstall_exitsOneForUnknownProfile() {
            int exit = runFull("profiles", "reinstall", "nonexistent-profile-xyz",
                "--project-dir", tempDir.toString());

            assertThat(exit)
                .as("profiles reinstall with unknown profile must exit 1")
                .isEqualTo(1);
            assertThat(capturedOutput())
                .as("profiles reinstall with unknown profile must report 'not found'")
                .containsIgnoringCase("not found");
        }
    }

    @Nested
    @DisplayName("profiles edit")
    class EditCommand {

        @Test
        @DisplayName("should exit 1 and report invalid profile name when given a path-traversal name")
        void editProfile_rejectsTraversalName_exits1WithError() {
            int exit = run("profiles", "edit", "../../etc/injected");

            assertThat(exit)
                .as("profiles edit with traversal name must exit 1")
                .isEqualTo(1);
            assertThat(output())
                .as("profiles edit with traversal name must print 'invalid profile name'")
                .containsIgnoringCase("invalid profile name");
        }

        @Test
        @DisplayName("should exit 1 and mention EDITOR when the EDITOR env var is not set")
        void edit_exitsOneWhenEditorNotSet() {
            var editCmd = new ProfilesCommand.EditCommand();
            editCmd.ccxConfigService = new CcxConfigService(ccxConfigDir);
            editCmd.name = "java";
            editCmd.editorSupplier = () -> null;

            var capture = new ByteArrayOutputStream();
            var cl = new CommandLine(editCmd);
            cl.setOut(new PrintWriter(capture, true));
            cl.setErr(new PrintWriter(capture, true));
            editCmd.spec = cl.getCommandSpec();

            int exit = editCmd.call();

            assertThat(exit)
                .as("profiles edit must exit 1 when EDITOR is not set")
                .isEqualTo(1);
            assertThat(capture.toString())
                .as("profiles edit must mention EDITOR in the error message")
                .containsIgnoringCase("EDITOR");
        }

        @Test
        @DisplayName("should exit 0 when EDITOR is a multi-word value like 'echo the-profile.yaml'")
        void edit_exitsZeroWhenEditorIsMultiWord() {
            var editCmd = new ProfilesCommand.EditCommand();
            editCmd.ccxConfigService = new CcxConfigService(ccxConfigDir);
            editCmd.name = "java";
            // Multi-word editor: "echo" + one extra arg. Before the fix, ProcessBuilder treats
            // the whole string as a single binary name → IOException (No such file or directory).
            // After the fix, it splits on whitespace → ProcessBuilder(["echo", "the-profile.yaml",
            // "/path/to/profile.yaml"]) which succeeds.
            editCmd.editorSupplier = () -> "echo the-profile.yaml";

            var capture = new ByteArrayOutputStream();
            var cl = new CommandLine(editCmd);
            cl.setOut(new PrintWriter(capture, true));
            cl.setErr(new PrintWriter(capture, true));
            editCmd.spec = cl.getCommandSpec();

            int exit = editCmd.call();

            assertThat(exit)
                .as("profiles edit must exit 0 when EDITOR contains multiple words (split correctly)")
                .isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("security — PathSecurity guard on mutating subcommands")
    class Security {

        @TempDir
        Path tempDir;

        private ByteArrayOutputStream capture;
        private CommandLine cl;

        @BeforeEach
        void setUpFull() {
            capture = new ByteArrayOutputStream();
            cl = buildFullCommandLine(capture);
        }

        private int runFull(String... args) {
            capture.reset();
            return cl.execute(args);
        }

        private String capturedOutput() {
            return capture.toString();
        }

        @Test
        @DisplayName("profiles enable should exit 1 and mention 'outside allowed' for /etc path")
        void enable_rejectsPathOutsideAllowed() {
            int exit = runFull("profiles", "enable", "java", "--project-dir", "/etc/malicious");

            assertThat(exit)
                .as("profiles enable with /etc path must exit 1")
                .isEqualTo(1);
            assertThat(capturedOutput())
                .as("profiles enable with /etc path must mention 'outside allowed'")
                .containsIgnoringCase("outside allowed");
        }

        @Test
        @DisplayName("profiles disable should exit 1 and mention 'outside allowed' for /etc path")
        void disable_rejectsPathOutsideAllowed() {
            int exit = runFull("profiles", "disable", "java", "--project-dir", "/etc/malicious");

            assertThat(exit)
                .as("profiles disable with /etc path must exit 1")
                .isEqualTo(1);
            assertThat(capturedOutput())
                .as("profiles disable with /etc path must mention 'outside allowed'")
                .containsIgnoringCase("outside allowed");
        }

        @Test
        @DisplayName("profiles reinstall should exit 1 and mention 'outside allowed' for /etc path")
        void reinstall_rejectsPathOutsideAllowed() {
            int exit = runFull("profiles", "reinstall", "java", "--project-dir", "/etc/malicious");

            assertThat(exit)
                .as("profiles reinstall with /etc path must exit 1")
                .isEqualTo(1);
            assertThat(capturedOutput())
                .as("profiles reinstall with /etc path must mention 'outside allowed'")
                .containsIgnoringCase("outside allowed");
        }
    }

    private CommandLine buildCommandLineWithCcxConfig(ByteArrayOutputStream capture) {
        var settingsService = new SettingsService();
        var ccxConfig = new CcxConfigService(ccxConfigDir);
        var factory = new TestCommandFactory(
            settingsService, buildProfileService(), new PluginMapService(),
            new ClaudeMdService(),
            new SkillsService(settingsService),
            new ProfileApplyService(), new CcxContextService(),
            ccxConfig
        );
        var cl = new CommandLine(new CcxCommand(), factory);
        cl.setOut(new PrintWriter(capture, true));
        cl.setErr(new PrintWriter(capture, true));
        return cl;
    }

    @Nested
    @DisplayName("profiles new")
    class New_ {

        @TempDir
        Path tempDir;

        private ByteArrayOutputStream capture;
        private CommandLine cl;

        @BeforeEach
        void setUpNew() {
            capture = new ByteArrayOutputStream();
            cl = buildCommandLineWithCcxConfig(capture);
        }

        private int runNew(String... args) {
            capture.reset();
            return cl.execute(args);
        }

        private String capturedOutput() {
            return capture.toString();
        }

        @Test
        @DisplayName("should create directory structure and profile.yaml")
        void new_createsDirectoryStructureAndProfileYaml() throws Exception {
            int exit = runNew("profiles", "new", "my-profile");

            assertThat(exit)
                .as("profiles new my-profile must exit 0")
                .isZero();
            var profileDir = ccxConfigDir.resolve("profiles/my-profile");
            assertThat(profileDir)
                .as("profile directory must be created")
                .isDirectory();
            assertThat(profileDir.resolve("profile.yaml"))
                .as("profile.yaml must be created")
                .exists();
            assertThat(profileDir.resolve("claudemd"))
                .as("claudemd/ subdirectory must be created")
                .isDirectory();
            assertThat(profileDir.resolve("rules"))
                .as("rules/ subdirectory must be created")
                .isDirectory();
            assertThat(profileDir.resolve("commands"))
                .as("commands/ subdirectory must be created")
                .isDirectory();
        }

        @Test
        @DisplayName("should write profile name into profile.yaml")
        void new_profileYamlContainsProfileName() throws Exception {
            runNew("profiles", "new", "my-profile");

            var yaml = Files.readString(ccxConfigDir.resolve("profiles/my-profile/profile.yaml"));
            assertThat(yaml)
                .as("profile.yaml must contain the profile name")
                .contains("name: my-profile");
        }

        @Test
        @DisplayName("should print confirmation containing the profile name")
        void new_printsConfirmationContainingProfileName() {
            runNew("profiles", "new", "my-profile");

            assertThat(capturedOutput())
                .as("output must mention the new profile name")
                .contains("my-profile");
        }

        @Test
        @DisplayName("should reject names with uppercase letters")
        void new_rejectsInvalidNameUppercase() {
            int exit = runNew("profiles", "new", "MyProfile");

            assertThat(exit)
                .as("profiles new with uppercase name must exit 1")
                .isEqualTo(1);
            assertThat(capturedOutput())
                .as("output must mention the invalid name")
                .contains("MyProfile");
        }

        @Test
        @DisplayName("should reject names containing underscores")
        void new_rejectsInvalidNameWithUnderscore() {
            int exit = runNew("profiles", "new", "my_profile");

            assertThat(exit)
                .as("profiles new with underscore name must exit 1")
                .isEqualTo(1);
            assertThat(capturedOutput())
                .as("output must mention the invalid name")
                .contains("my_profile");
        }

        @Test
        @DisplayName("should reject names containing slashes")
        void new_rejectsNameWithSlash() {
            int exit = runNew("profiles", "new", "bad/name");

            assertThat(exit)
                .as("profiles new with slash in name must exit 1")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("should exit 1 when profile already exists")
        void new_exitsOneWhenProfileAlreadyExists() {
            runNew("profiles", "new", "my-profile");

            int exit = runNew("profiles", "new", "my-profile");

            assertThat(exit)
                .as("second profiles new for same name must exit 1")
                .isEqualTo(1);
            assertThat(capturedOutput())
                .as("output must mention the conflicting profile name")
                .contains("my-profile");
        }
    }

    @Nested
    @DisplayName("first-run onboarding")
    class FirstRunOnboarding {

        @TempDir
        Path emptyConfigDir;

        private ByteArrayOutputStream capture;
        private CommandLine cl;

        @BeforeEach
        void setUpEmpty() {
            // No profiles/ entries under emptyConfigDir → listAvailableProfiles() is empty
            var settingsService = new SettingsService();
            var yamlMapper = new ObjectMapper(new YAMLFactory());
            yamlMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            yamlMapper.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
            var profileService = new ProfileService(yamlMapper, new CcxConfigService(emptyConfigDir));
            var factory = new TestCommandFactory(
                settingsService, profileService, new PluginMapService(),
                new ClaudeMdService()
            );
            capture = new ByteArrayOutputStream();
            cl = new CommandLine(new CcxCommand(), factory);
            cl.setOut(new PrintWriter(capture, true));
            cl.setErr(new PrintWriter(capture, true));
        }

        private int runEmpty(String... args) {
            capture.reset();
            return cl.execute(args);
        }

        private String capturedOutput() {
            return capture.toString();
        }

        @Test
        @DisplayName("profiles list with no profiles should prompt user to create a profile")
        void list_emptyProfiles_pointsAtStarters() {
            runEmpty("profiles", "list");

            assertThat(capturedOutput())
                .as("empty profiles list must prompt users to create a profile")
                .contains("ccx profiles new");
        }

        @Test
        @DisplayName("profiles help should not promise bundled built-in profiles")
        void profilesHelp_doesNotPromiseBuiltIn() {
            runEmpty("profiles", "--help");

            assertThat(capturedOutput())
                .as("profiles help must not claim it manages 'built-in' profiles — none ship in the binary")
                .doesNotContainIgnoringCase("built-in");
        }
    }

    @Nested
    @DisplayName("profiles status")
    class Status {

        @TempDir
        Path tempDir;

        private ByteArrayOutputStream capture;
        private CommandLine cl;

        @BeforeEach
        void setUpFull() {
            capture = new ByteArrayOutputStream();
            cl = buildFullCommandLine(capture);
        }

        private int runFull(String... args) {
            capture.reset();
            return cl.execute(args);
        }

        private String capturedOutput() {
            return capture.toString();
        }

        @Test
        @DisplayName("should exit 0 and list all profiles with a state indicator")
        void status_exitsZeroAndListsProfilesWithState() {
            int exit = runFull("profiles", "status", "--project-dir", tempDir.toString());

            assertThat(exit)
                .as("profiles status must exit 0")
                .isZero();
            assertThat(capturedOutput())
                .as("profiles status must list profile names")
                .contains("java");
            assertThat(capturedOutput())
                .as("profiles status must include a state indicator (ENABLED, DISABLED, or ALTERED)")
                .containsAnyOf("ENABLED", "DISABLED", "ALTERED");
            assertThat(capturedOutput())
                .as("profiles status must list the go profile")
                .contains("go");
            assertThat(capturedOutput())
                .as("profiles status must list the frontend profile")
                .contains("frontend");
            assertThat(capturedOutput())
                .as("profiles status must list the bash profile")
                .contains("bash");
        }

        @Test
        @DisplayName("should exit 1 and mention 'outside allowed' when --project-dir is outside allowed paths")
        void statusCommand_rejectsProjectDirOutsideAllowed() {
            int exit = runFull("profiles", "status", "--project-dir", "/etc");

            assertThat(exit)
                .as("profiles status with /etc project-dir must exit 1")
                .isEqualTo(1);
            assertThat(capturedOutput())
                .as("profiles status with /etc project-dir must mention 'outside allowed'")
                .containsIgnoringCase("outside allowed");
        }

        @Test
        @DisplayName("should show ALTERED when an enabled profile has drift (plugin manually removed)")
        void status_showsAltered_whenEnabledProfileHasDrift() throws Exception {
            // Enable java profile — writes context.json and settings.json
            runFull("profiles", "enable", "java", "--project-dir", tempDir.toString());

            // Simulate drift: manually remove the java plugin entry from enabledPlugins
            var settingsService = new me.neveux.ccx.service.SettingsService();
            var settings = settingsService.read(tempDir);
            if (settings.enabledPlugins() != null) {
                var mutable = new java.util.LinkedHashMap<>(settings.enabledPlugins());
                mutable.entrySet().removeIf(e -> e.getKey().startsWith("java@"));
                settings = settings.withEnabledPlugins(mutable);
                settingsService.write(settings, tempDir);
            }

            int exit = runFull("profiles", "status", "--project-dir", tempDir.toString());

            assertThat(exit)
                .as("profiles status must exit 0 even when drift is detected")
                .isZero();
            assertThat(capturedOutput())
                .as("profiles status must report ALTERED when enabled profile has drift")
                .contains("ALTERED");
        }

        @Test
        @DisplayName("should report BROKEN when a dependency is disabled out from under a dependent (Wave 6)")
        void status_showsBroken_whenDependencyDisabled() throws Exception {
            // java-functional extends java; java exists from writeTestProfiles
            var dir = ccxConfigDir.resolve("profiles/java-functional");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("profile.yaml"),
                "metadata:\n  name: java-functional\nextends:\n  - java\n"
                + "plugins:\n  enable:\n    - java-functional@claude-java-stack\n");

            // Enabling java-functional auto-enables java (Wave 6A)
            runFull("profiles", "enable", "java-functional", "--project-dir", tempDir.toString());
            // Disable the dependency out from under it
            runFull("profiles", "disable", "java", "--project-dir", tempDir.toString());

            int exit = runFull("profiles", "status", "--project-dir", tempDir.toString());

            assertThat(exit)
                .as("profiles status must exit 0 even when a dependent is broken")
                .isZero();
            assertThat(capturedOutput())
                .as("profiles status must report BROKEN for java-functional after its dependency java is disabled")
                .contains("BROKEN");
        }
    }
}
