package me.neveux.ccx.it;

import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the packaged binary — the native executable under {@code -Pnative} — against a fixture
 * project, so the closed-world analysis is exercised instead of merely built.
 *
 * <p>Reflection gaps are invisible to the JVM suite: a missing {@code @RegisterForReflection} on a
 * model record or its Jackson builder only fails when the native image deserializes real JSON. Each
 * test here therefore reads a fixture that covers the whole serde surface — {@code Settings},
 * {@code Permissions} (builder + unknown-key passthrough), {@code McpServer} (builder + passthrough),
 * {@code CcxContext}, {@code ProfileRecord} with an {@code Instant}, and {@code ProfileSnapshot} —
 * and asserts on output that only appears if the parse succeeded.
 *
 * <p>Only read-only invocations are used: {@code clean} without {@code --force} reports what it
 * would remove and writes nothing, so the fixture is still intact afterwards (asserted).
 */
@QuarkusMainIntegrationTest
@DisplayName("Native binary: real invocations against a fixture project")
class CcxNativeIT {

    private static final String SETTINGS_JSON = """
        {
          "permissions": {
            "allow": ["Read(//tmp/**)"],
            "deny": ["Bash(rm:*)"],
            "defaultMode": "acceptEdits"
          },
          "mcpServers": {
            "fixture-server": {
              "type": "stdio",
              "command": "echo",
              "args": ["hello"],
              "env": { "FIXTURE_TOKEN": "not-a-secret" }
            }
          },
          "skillOverrides": { "java:java": "off" }
        }
        """;

    private static final String CONTEXT_JSON = """
        {
          "version": "1",
          "enabledProfiles": {
            "native-fixture-profile": {
              "enabledAt": "2026-08-27T10:15:30Z",
              "snapshot": {
                "plugins": ["marketplace:plugin"],
                "rules": [".claude/rules/native-fixture.md"],
                "permissionsAllow": ["Read(//tmp/**)"],
                "permissionsDeny": ["Bash(rm:*)"],
                "commands": [],
                "fragments": [],
                "env": { "FIXTURE": "1" },
                "ignorePatterns": []
              }
            }
          }
        }
        """;

    private Path projectDir;

    @BeforeEach
    void createFixture() throws IOException {
        projectDir = Files.createTempDirectory("ccx-native-it");
        Files.createDirectories(projectDir.resolve(".claude"));
        Files.createDirectories(projectDir.resolve(".ccx"));
        Files.writeString(projectDir.resolve(".claude/settings.json"), SETTINGS_JSON);
        Files.writeString(projectDir.resolve(".ccx/context.json"), CONTEXT_JSON);
    }

    @AfterEach
    void deleteFixture() throws IOException {
        if (projectDir == null || !Files.exists(projectDir)) return;
        try (var paths = Files.walk(projectDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        }
    }

    @Test
    @DisplayName("--version reports the build version, proving the binary starts at all")
    void version_isReported(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("--version");

        assertThat(result.getOutput())
            .as("the binary must print its own version banner")
            .contains("ccx ");
        assertThat(result.exitCode())
            .as("--version must exit 0")
            .isZero();
    }

    /**
     * A missing reflection entry on {@code Settings}, {@code Permissions} or {@code McpServer} (or on
     * either Jackson builder) surfaces here as {@code [ERROR] settings.json is malformed}, so the
     * assertion on the OK line is the reflection check. Requires {@code git} on PATH — doctor treats
     * its absence as an error, which is the documented contract.
     */
    @Test
    @DisplayName("doctor parses a populated settings.json and reports its MCP server")
    void doctor_onPopulatedFixture_parsesTheSettingsModel(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("doctor", "--project-dir", projectDir.toString());
        var combined = result.getOutput() + result.getErrorOutput();

        assertThat(combined)
            .as("settings.json with permissions + an MCP server carrying unmodelled keys must parse "
                + "in the native image")
            .contains("[OK] settings.json is valid JSON")
            .contains("1 MCP server(s): fixture-server (stdio)")
            .contains("[INFO] 1 skill override(s)");
        assertThat(result.exitCode())
            .as("a fixture with no enabled plugins and no broken refs must pass every doctor check")
            .isZero();
    }

    /**
     * {@code clean} without {@code --force} is the only read-only command that echoes
     * {@code .ccx/context.json} content, so it is what proves {@code CcxContext} and its nested
     * {@code Instant}-bearing records deserialize in the native image.
     */
    @Test
    @DisplayName("clean without --force echoes the recorded profile and leaves the fixture intact")
    void cleanDryRun_echoesRecordedProfile_andWritesNothing(QuarkusMainLauncher launcher) throws IOException {
        var contextFile = projectDir.resolve(".ccx/context.json");
        var before = Files.readString(contextFile);

        LaunchResult result = launcher.launch("clean", "--project-dir", projectDir.toString());
        var combined = result.getOutput() + result.getErrorOutput();

        assertThat(combined)
            .as("the dry run must name the profile recorded in .ccx/context.json")
            .contains("Would remove ccx-managed content")
            .contains("native-fixture-profile");
        assertThat(result.exitCode())
            .as("a dry-run clean must exit 0")
            .isZero();
        assertThat(Files.readString(contextFile))
            .as("clean without --force must not modify .ccx/context.json")
            .isEqualTo(before);
    }
}
