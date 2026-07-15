package me.neveux.ccx.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import me.neveux.ccx.service.CcxConfigService;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.PluginMapService;
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

@DisplayName("ClaudeMdCommand")
class ClaudeMdCommandTest {

    @TempDir
    Path projectDir;

    @TempDir
    Path ccxConfigDir;

    private CommandLine commandLine;
    private final ByteArrayOutputStream outputCapture = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws Exception {
        var javaProfileDir = ccxConfigDir.resolve("profiles/java");
        Files.createDirectories(javaProfileDir);
        Files.writeString(javaProfileDir.resolve("profile.yaml"),
            "metadata:\n  name: java\n  description: Java/Maven projects\nplugins:\n  enable:\n    - java@claude-java-stack\nrules:\n  files:\n    - java\n");
        var yamlMapper = new ObjectMapper(new YAMLFactory());
        yamlMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        yamlMapper.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
        var profileService = new ProfileService(yamlMapper, new CcxConfigService(ccxConfigDir));
        var settingsService = new SettingsService();
        var factory = new TestCommandFactory(
            settingsService, profileService, new PluginMapService(),
            new ClaudeMdService(),
            new SkillsService(settingsService),
            null, null,
            new CcxConfigService(ccxConfigDir)
        );
        commandLine = new CommandLine(new CcxCommand(), factory);
        commandLine.setOut(new PrintWriter(outputCapture, true));
        commandLine.setErr(new PrintWriter(outputCapture, true));
    }

    private int run(String... args) {
        outputCapture.reset();
        return commandLine.execute(args);
    }

    private String output() {
        return outputCapture.toString();
    }

    private Path claudeMd() {
        return projectDir.resolve(".claude/CLAUDE.md");
    }

    private void writeClaudeMd(String content) throws Exception {
        Files.createDirectories(claudeMd().getParent());
        Files.writeString(claudeMd(), content);
    }

    @Nested
    @DisplayName("claude-md list")
    class List_ {

        @Test
        @DisplayName("should print (no fragments configured) when .claude/CLAUDE.md has no marker block")
        void list_printsNoFragmentsConfigured_whenNoMarkerBlock() throws Exception {
            writeClaudeMd("# My Project\n\nSome content here.\n");

            int exit = run("claude-md", "list", "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("list with no marker block must exit 0")
                .isZero();
            assertThat(output())
                .as("list with no marker block must print the empty message with the shared Quips.EMPTY treatment")
                .contains("no fragments configured")
                .contains(me.neveux.ccx.tui.Quips.EMPTY[0]);
        }

        @Test
        @DisplayName("should print each fragment line when marker block has two entries")
        void list_printsBothLines_whenTwoFragmentsConfigured() throws Exception {
            writeClaudeMd("""
                # My Project
                <!-- ccx:begin -->
                @/home/user/.config/ccx/claudemd/java.md
                @/home/user/.config/ccx/claudemd/bash.md
                <!-- ccx:end -->
                """);

            int exit = run("claude-md", "list", "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("list must exit 0")
                .isZero();
            assertThat(output())
                .as("list must print the first fragment line")
                .contains("@/home/user/.config/ccx/claudemd/java.md");
            assertThat(output())
                .as("list must print the second fragment line")
                .contains("@/home/user/.config/ccx/claudemd/bash.md");
        }
    }

    @Nested
    @DisplayName("claude-md list — security")
    class ListSecurity {

        @Test
        @DisplayName("should exit 1 and mention 'outside allowed' when --project-dir is outside allowed paths")
        void listCommand_rejectsProjectDirOutsideAllowed() {
            int exit = run("claude-md", "list", "--project-dir", "/etc");

            assertThat(exit)
                .as("claude-md list with /etc project-dir must exit 1")
                .isEqualTo(1);
            assertThat(output())
                .as("claude-md list with /etc project-dir must mention 'outside allowed'")
                .containsIgnoringCase("outside allowed");
        }
    }

    @Nested
    @DisplayName("claude-md add")
    class Add {

        @BeforeEach
        void createGlobalFragment() throws Exception {
            var globalDir = ccxConfigDir.resolve("claudemd");
            Files.createDirectories(globalDir);
            Files.writeString(globalDir.resolve("java-debug.md"), "# Java Debug\n");
        }

        @Test
        @DisplayName("should add fragment line to .claude/CLAUDE.md and print Added message")
        void add_updatesFileAndPrintsAdded_whenFragmentExists() throws Exception {
            writeClaudeMd("<!-- ccx:begin -->\n<!-- ccx:end -->\n");

            int exit = run("claude-md", "add", "java-debug", "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("add must exit 0")
                .isZero();
            assertThat(output())
                .as("add must print Added message")
                .contains("Added:");
            assertThat(Files.readString(claudeMd()))
                .as(".claude/CLAUDE.md must contain the fragment line after add")
                .contains("java-debug.md");
        }

        @Test
        @DisplayName("should exit 1 when fragment does not exist")
        void add_exitsOneWhenFragmentMissing() throws Exception {
            writeClaudeMd("<!-- ccx:begin -->\n<!-- ccx:end -->\n");

            int exit = run("claude-md", "add", "nonexistent", "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("add with missing fragment must exit 1")
                .isEqualTo(1);
            assertThat(output())
                .as("add with missing fragment must report not found")
                .contains("not found");
        }

        @Test
        @DisplayName("should print already present when duplicate add")
        void add_printsAlreadyPresent_whenDuplicate() throws Exception {
            writeClaudeMd("<!-- ccx:begin -->\n<!-- ccx:end -->\n");
            run("claude-md", "add", "java-debug", "--project-dir", projectDir.toString());

            int exit = run("claude-md", "add", "java-debug", "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("duplicate add must exit 0")
                .isZero();
            assertThat(output())
                .as("duplicate add must print already present message")
                .contains("already present");
        }

        @Test
        @DisplayName("should reject a name containing path traversal")
        void add_rejectsTraversalName() throws Exception {
            writeClaudeMd("<!-- ccx:begin -->\n<!-- ccx:end -->\n");

            int exit = run("claude-md", "add", "../../etc/passwd", "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("add with path traversal must exit non-zero")
                .isNotZero();
            assertThat(output())
                .as("add with path traversal must report error")
                .contains("Error");
        }
    }

    @Nested
    @DisplayName("claude-md remove")
    class Remove {

        @BeforeEach
        void createGlobalFragment() throws Exception {
            var globalDir = ccxConfigDir.resolve("claudemd");
            Files.createDirectories(globalDir);
            Files.writeString(globalDir.resolve("java-debug.md"), "# Java Debug\n");
        }

        @Test
        @DisplayName("should remove fragment line from .claude/CLAUDE.md and print Removed message")
        void remove_updatesFileAndPrintsRemoved() throws Exception {
            writeClaudeMd("<!-- ccx:begin -->\n<!-- ccx:end -->\n");
            run("claude-md", "add", "java-debug", "--project-dir", projectDir.toString());

            int exit = run("claude-md", "remove", "java-debug", "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("remove must exit 0")
                .isZero();
            assertThat(output())
                .as("remove must print Removed message")
                .contains("Removed:");
            assertThat(Files.readString(claudeMd()))
                .as(".claude/CLAUDE.md must no longer contain the fragment line")
                .doesNotContain("java-debug.md");
        }

        @Test
        @DisplayName("should exit 1 and mention 'invalid characters' when name contains path traversal")
        void removeCommand_rejectsUnsafeName() throws Exception {
            writeClaudeMd("<!-- ccx:begin -->\n<!-- ccx:end -->\n");

            int exit = run("claude-md", "remove", "../etc/passwd", "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("claude-md remove with path traversal name must exit 1")
                .isEqualTo(1);
            assertThat(output())
                .as("claude-md remove with path traversal name must mention 'invalid characters'")
                .containsIgnoringCase("invalid characters");
        }

        @Test
        @DisplayName("should print not found and exit 0 when fragment is absent")
        void remove_printsNotFound_whenAbsent() throws Exception {
            writeClaudeMd("<!-- ccx:begin -->\n<!-- ccx:end -->\n");

            int exit = run("claude-md", "remove", "java-debug", "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("remove on absent fragment must exit 0")
                .isZero();
            assertThat(output())
                .as("remove on absent fragment must print not found message")
                .contains("not found");
        }
    }

    @Nested
    @DisplayName("claude-md init")
    class Init {

        @Test
        @DisplayName("should create .claude/CLAUDE.md with empty marker block and print Initialized")
        void init_createsClaudeMd_whenFileDoesNotExist() {
            int exit = run("claude-md", "init", "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("init must exit 0 when .claude/CLAUDE.md does not exist")
                .isZero();
            assertThat(output())
                .as("init must print Initialized .claude/CLAUDE.md")
                .contains("Initialized .claude/CLAUDE.md");
            assertThat(claudeMd())
                .as(".claude/CLAUDE.md must be created")
                .exists();
        }

        @Test
        @DisplayName("should print already exists message and leave file unchanged when .claude/CLAUDE.md exists")
        void init_printsAlreadyExists_whenClaudeMdAlreadyExists() throws Exception {
            var original = "# Existing project\n\nDo not overwrite me.\n";
            writeClaudeMd(original);

            int exit = run("claude-md", "init", "--project-dir", projectDir.toString());

            assertThat(exit)
                .as("init on existing .claude/CLAUDE.md must exit 0")
                .isZero();
            assertThat(output())
                .as("init on existing .claude/CLAUDE.md must print already exists message")
                .contains("already exists");
            assertThat(Files.readString(claudeMd()))
                .as("init must not modify an existing .claude/CLAUDE.md")
                .isEqualTo(original);
        }
    }
}
