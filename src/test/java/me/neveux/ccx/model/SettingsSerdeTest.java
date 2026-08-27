package me.neveux.ccx.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.neveux.ccx.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Settings JSON serialization round-trip")
class SettingsSerdeTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Nested
    @DisplayName("when serializing and deserializing a fully populated Settings")
    class WhenFullyPopulated {

        private Settings buildFullSettings() {
            var mcp = new McpServer("sse", "http://localhost:8080/sse", null, null);
            var permissions = new Permissions(
                List.of("Bash(mvn *)", "Bash(gradle *)"),
                List.of("Bash(rm -rf *)"));
            var source = new MarketplaceSource("my-marketplace", "/home/user/.claude/plugins", null, null);

            return new Settings()
                .withEnabledPlugins(Map.of(
                    "java@claude-java-stack", true,
                    "java-core@claude-java-stack", false
                ))
                .withEnv(Map.of(
                    "ENABLE_LSP_TOOL", "1",
                    "DEBUG", "false"
                ))
                .withSkillOverrides(Map.of(
                    "java-testing", "user-invocable-only"
                ))
                .withMcpServers(Map.of("my-server", mcp))
                .withPermissions(permissions)
                .withExtraKnownMarketplaces(List.of(source));
        }

        @Test
        @DisplayName("should preserve enabledPlugins through JSON round-trip")
        void preservesEnabledPlugins() throws Exception {
            var original = buildFullSettings();

            var json = mapper.writeValueAsString(original);
            var restored = mapper.readValue(json, Settings.class);

            assertThat(restored.enabledPlugins())
                .as("enabledPlugins must survive round-trip")
                .containsEntry("java@claude-java-stack", true)
                .containsEntry("java-core@claude-java-stack", false);
        }

        @Test
        @DisplayName("should preserve env through JSON round-trip")
        void preservesEnv() throws Exception {
            var original = buildFullSettings();

            var json = mapper.writeValueAsString(original);
            var restored = mapper.readValue(json, Settings.class);

            assertThat(restored.env())
                .as("env map must survive round-trip")
                .containsEntry("ENABLE_LSP_TOOL", "1")
                .containsEntry("DEBUG", "false");
        }

        @Test
        @DisplayName("should preserve skillOverrides through JSON round-trip")
        void preservesSkillOverrides() throws Exception {
            var original = buildFullSettings();

            var json = mapper.writeValueAsString(original);
            var restored = mapper.readValue(json, Settings.class);

            assertThat(restored.skillOverrides())
                .as("skillOverrides must survive round-trip")
                .containsEntry("java-testing", "user-invocable-only");
        }

        @Test
        @DisplayName("should preserve mcpServers through JSON round-trip")
        void preservesMcpServers() throws Exception {
            var original = buildFullSettings();

            var json = mapper.writeValueAsString(original);
            var restored = mapper.readValue(json, Settings.class);

            assertThat(restored.mcpServers())
                .as("mcpServers map must survive round-trip")
                .containsKey("my-server");
            assertThat(restored.mcpServers().get("my-server").type())
                .isEqualTo("sse");
            assertThat(restored.mcpServers().get("my-server").url())
                .isEqualTo("http://localhost:8080/sse");
        }

        @Test
        @DisplayName("should preserve permissions allow and deny lists through JSON round-trip")
        void preservesPermissions() throws Exception {
            var original = buildFullSettings();

            var json = mapper.writeValueAsString(original);
            var restored = mapper.readValue(json, Settings.class);

            assertThat(restored.permissions())
                .as("permissions must not be null after round-trip")
                .isNotNull();
            assertThat(restored.permissions().allow())
                .as("permissions.allow must survive round-trip")
                .containsExactlyInAnyOrder("Bash(mvn *)", "Bash(gradle *)");
            assertThat(restored.permissions().deny())
                .as("permissions.deny must survive round-trip")
                .containsExactly("Bash(rm -rf *)");
        }

        @Test
        @DisplayName("should preserve extraKnownMarketplaces through SettingsService write/read round-trip")
        void preservesExtraKnownMarketplaces(@TempDir Path tempDir) throws Exception {
            var original = buildFullSettings();

            var service = new SettingsService();
            service.write(original, tempDir);
            var restored = service.read(tempDir);

            assertThat(restored.extraKnownMarketplaces())
                .as("extraKnownMarketplaces must survive round-trip")
                .hasSize(1);
            assertThat(restored.extraKnownMarketplaces().get(0).key())
                .isEqualTo("my-marketplace");
            assertThat(restored.extraKnownMarketplaces().get(0).path())
                .isEqualTo("/home/user/.claude/plugins");
        }

        @Test
        @DisplayName("should omit null fields from serialized JSON")
        void nullFieldsOmittedFromJson() throws Exception {
            var settings = new Settings()
                .withEnabledPlugins(Map.of("plugin-a", true));

            var json = mapper.writeValueAsString(settings);

            assertThat(json)
                .as("null skillOverrides must not appear in JSON")
                .doesNotContain("skillOverrides");
            assertThat(json)
                .as("null hooks must not appear in JSON")
                .doesNotContain("hooks");
            assertThat(json)
                .as("null mcpServers must not appear in JSON")
                .doesNotContain("mcpServers");
        }

        @Test
        @DisplayName("should serialize extraKnownMarketplaces as native Claude Code object format")
        void extraKnownMarketplaces_serializesAsObjectFormat() throws Exception {
            var source = new MarketplaceSource("claude-java-stack", "/home/user/projects/claude-java-stack", null, null);
            var settings = new Settings()
                .withExtraKnownMarketplaces(List.of(source));

            var json = mapper.writeValueAsString(settings);

            assertThat(json)
                .as("marketplace must be serialized as object keyed by name, not as array")
                .contains("\"claude-java-stack\"")
                .contains("\"directory\"")
                .contains("/home/user/projects/claude-java-stack")
                .doesNotContain("[{");
        }

        @Test
        @DisplayName("should round-trip extraKnownMarketplaces through SettingsService write/read")
        void extraKnownMarketplaces_roundTripViaSettingsService(@TempDir Path tempDir) throws Exception {
            var source = new MarketplaceSource("claude-java-stack", "/home/user/projects/claude-java-stack", null, null);
            var settings = new Settings()
                .withExtraKnownMarketplaces(List.of(source));

            var service = new SettingsService();
            service.write(settings, tempDir);
            var restored = service.read(tempDir);

            assertThat(restored.extraKnownMarketplaces())
                .as("marketplace must survive write/read round-trip")
                .hasSize(1);
            assertThat(restored.extraKnownMarketplaces().get(0).key())
                .isEqualTo("claude-java-stack");
            assertThat(restored.extraKnownMarketplaces().get(0).path())
                .isEqualTo("/home/user/projects/claude-java-stack");
        }

        @Test
        @DisplayName("should preserve hooks map through ObjectMapper JSON round-trip directly")
        void hooks_roundTripViaObjectMapperDirectly() throws Exception {
            var settings = new Settings()
                .withHooks(Map.of(
                    "PreToolUse", List.of(
                        Map.of(
                            "matcher", "Bash",
                            "hooks", List.of(Map.of("type", "command", "command", "echo direct"))
                        )
                    )
                ));

            var json = mapper.writeValueAsString(settings);
            var restored = mapper.readValue(json, Settings.class);

            assertThat(restored.hooks())
                .as("hooks map must survive ObjectMapper round-trip")
                .isNotNull()
                .containsKey("PreToolUse");
        }

        @Test
        @DisplayName("should preserve hooks map through write/read round-trip via SettingsService")
        void hooks_surviveRoundTrip(@TempDir Path tempDir) throws Exception {
            var settings = new Settings()
                .withHooks(Map.of(
                    "PreToolUse", List.of(
                        Map.of(
                            "matcher", "Bash",
                            "hooks", List.of(Map.of("type", "command", "command", "echo test"))
                        )
                    )
                ));

            var service = new SettingsService();
            service.write(settings, tempDir);
            var restored = service.read(tempDir);

            assertThat(restored.hooks())
                .as("hooks map must survive write/read round-trip")
                .isNotNull()
                .containsKey("PreToolUse");
        }

        @Test
        @DisplayName("should preserve MCP server args list through write/read round-trip via SettingsService")
        void mcpServer_args_surviveRoundTrip(@TempDir Path tempDir) throws Exception {
            var mcp = new McpServer("stdio", null, "my-server", List.of("--port", "8080"));
            var settings = new Settings()
                .withMcpServers(Map.of("my-server", mcp));

            var service = new SettingsService();
            service.write(settings, tempDir);
            var restored = service.read(tempDir);

            assertThat(restored.mcpServers().get("my-server").args())
                .as("MCP server args must survive write/read round-trip")
                .containsExactly("--port", "8080");
        }
    }

    @Nested
    @DisplayName("when deserializing Claude Code native extraKnownMarketplaces object format")
    class WhenClaudeCodeNativeFormat {

        @Test
        @DisplayName("should parse extraKnownMarketplaces as object keyed by name")
        void parsesObjectFormatMarketplaces() throws Exception {
            var json = """
                {
                  "extraKnownMarketplaces": {
                    "claude-java-stack": {
                      "source": {
                        "source": "directory",
                        "path": "/home/user/projects/claude-java-stack"
                      }
                    }
                  }
                }
                """;

            var restored = mapper.readValue(json, Settings.class);

            assertThat(restored.extraKnownMarketplaces())
                .as("object-format marketplaces must be parsed into list")
                .hasSize(1);
            assertThat(restored.extraKnownMarketplaces().get(0).key())
                .isEqualTo("claude-java-stack");
            assertThat(restored.extraKnownMarketplaces().get(0).path())
                .isEqualTo("/home/user/projects/claude-java-stack");
        }

        @Test
        @DisplayName("should include github marketplace entries (no path, has repo)")
        void includesGithubEntries() throws Exception {
            var json = """
                {
                  "extraKnownMarketplaces": {
                    "github-only": {
                      "source": {
                        "source": "github",
                        "repo": "anthropics/claude-plugins-official"
                      }
                    }
                  }
                }
                """;

            var restored = mapper.readValue(json, Settings.class);

            assertThat(restored.extraKnownMarketplaces())
                .as("github-sourced marketplace must be included so MarketplaceCloneService can fetch it")
                .hasSize(1);
            assertThat(restored.extraKnownMarketplaces().get(0).key()).isEqualTo("github-only");
            assertThat(restored.extraKnownMarketplaces().get(0).githubRepo())
                .isEqualTo("anthropics/claude-plugins-official");
        }
    }

    @Nested
    @DisplayName("when deserializing minimal or empty JSON")
    class WhenMinimal {

        @Test
        @DisplayName("should deserialize empty JSON object without throwing")
        void deserializesEmptyObject() throws Exception {
            var restored = mapper.readValue("{}", Settings.class);

            assertThat(restored).isNotNull();
        }

        @Test
        @DisplayName("should deserialize JSON with unknown fields without throwing")
        void ignoresUnknownFields() throws Exception {
            var json = "{\"unknownField\": \"value\", \"anotherUnknown\": 42}";

            var restored = mapper.readValue(json, Settings.class);

            assertThat(restored).isNotNull();
        }
    }

    @Nested
    @DisplayName("when permissions has null deny")
    class WhenPartialPermissions {

        @Test
        @DisplayName("should not include deny key in JSON when deny is null")
        void permissions_nullDeny_serializedWithoutDenyKey() throws Exception {
            var permissions = new Permissions(List.of("Bash(mvn *)"), null);
            var settings = new Settings()
                .withPermissions(permissions);

            var json = mapper.writeValueAsString(settings);

            assertThat(json)
                .as("null deny must not appear as a key in the serialized JSON")
                .doesNotContain("deny");
        }

        @Test
        @DisplayName("should preserve allow and leave deny null through write/read round-trip")
        void permissions_withNullDeny_survivesRoundTrip(@TempDir Path tempDir) throws Exception {
            var permissions = new Permissions(List.of("/tmp/**"), null);
            var settings = new Settings()
                .withPermissions(permissions);

            var service = new me.neveux.ccx.service.SettingsService();
            service.write(settings, tempDir);
            var restored = service.read(tempDir);

            assertThat(restored.permissions())
                .as("permissions must not be null after round-trip")
                .isNotNull();
            assertThat(restored.permissions().allow())
                .as("permissions.allow must survive round-trip")
                .containsExactly("/tmp/**");
            assertThat(restored.permissions().deny())
                .as("permissions.deny must remain null when not set")
                .isNull();
        }
    }

    @Nested
    @DisplayName("when nested objects carry keys ccx does not model")
    class WhenNestedUnknownKeys {

        @Test
        @DisplayName("permissions keys ccx does not model survive a write/read round-trip")
        void permissions_unknownKeys_surviveRoundTrip(@TempDir Path tempDir) throws Exception {
            var settingsFile = tempDir.resolve(".claude/settings.json");
            Files.createDirectories(settingsFile.getParent());
            Files.writeString(settingsFile, """
                {
                  "permissions": {
                    "allow": ["Bash(mvn:*)"],
                    "defaultMode": "acceptEdits",
                    "additionalDirectories": ["../shared"]
                  }
                }
                """);
            var service = new SettingsService();

            var loaded = service.read(tempDir);
            service.write(loaded.withPermissions(loaded.permissions().withAllow(List.of("Bash(mvn:*)", "Bash(git:*)"))),
                tempDir);

            var json = mapper.readTree(settingsFile.toFile());
            assertThat(json.path("permissions").path("defaultMode").asText())
                .as("Claude Code owns permissions.defaultMode — a ccx write must not drop it")
                .isEqualTo("acceptEdits");
            assertThat(json.path("permissions").path("additionalDirectories").isArray()).isTrue();
            assertThat(json.path("permissions").path("allow").toString()).contains("Bash(git:*)");
        }

        @Test
        @DisplayName("mcpServers keys ccx does not model survive a write/read round-trip")
        void mcpServers_unknownKeys_surviveRoundTrip(@TempDir Path tempDir) throws Exception {
            var settingsFile = tempDir.resolve(".claude/settings.json");
            Files.createDirectories(settingsFile.getParent());
            Files.writeString(settingsFile, """
                {
                  "mcpServers": {
                    "github": {
                      "type": "http",
                      "url": "https://api.example.test/mcp",
                      "headers": {"Authorization": "Bearer ${TOKEN}"},
                      "env": {"HOME_OVERRIDE": "/tmp"}
                    }
                  }
                }
                """);
            var service = new SettingsService();

            var loaded = service.read(tempDir);
            service.write(loaded, tempDir);

            var server = mapper.readTree(settingsFile.toFile()).path("mcpServers").path("github");
            assertThat(server.path("headers").path("Authorization").asText())
                .as("an MCP server's headers are not modelled by ccx but must round-trip")
                .isEqualTo("Bearer ${TOKEN}");
            assertThat(server.path("env").path("HOME_OVERRIDE").asText()).isEqualTo("/tmp");
            assertThat(server.path("url").asText()).isEqualTo("https://api.example.test/mcp");
        }

        @Test
        @DisplayName("an MCP server's modelled-but-absent fields are not written back as nulls")
        void mcpServers_absentFields_areNotSerializedAsNull(@TempDir Path tempDir) throws Exception {
            var settingsFile = tempDir.resolve(".claude/settings.json");
            Files.createDirectories(settingsFile.getParent());
            Files.writeString(settingsFile, """
                {"mcpServers": {"local": {"command": "npx", "args": ["-y", "server"]}}}
                """);
            var service = new SettingsService();

            service.write(service.read(tempDir), tempDir);

            var server = mapper.readTree(settingsFile.toFile()).path("mcpServers").path("local");
            assertThat(server.has("url"))
                .as("writing url:null would make Claude Code read a stdio server as a broken http one")
                .isFalse();
            assertThat(server.has("type")).isFalse();
            assertThat(server.path("command").asText()).isEqualTo("npx");
        }
    }
}
