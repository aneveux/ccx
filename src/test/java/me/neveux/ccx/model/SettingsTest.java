package me.neveux.ccx.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.neveux.ccx.config.ObjectMapperConfig;
import me.neveux.ccx.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD baseline — all tests in this class FAIL until @JsonAnySetter/@JsonAnyGetter
 * passthrough is added to the Settings record.
 *
 * Done-when criterion: a settings.json with an unknown key (e.g. "model")
 * round-trips through Settings serialization with the key preserved.
 */
@DisplayName("Settings — unknown key passthrough (TDD baseline)")
class SettingsTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = ObjectMapperConfig.defaultJson();
    }

    @Test
    @DisplayName("unknown key 'model' survives ObjectMapper round-trip")
    void unknownKey_survivesObjectMapperRoundTrip() throws Exception {
        var json = "{\"model\":\"claude-opus-4-8\",\"enabledPlugins\":{\"java\":true}}";

        var settings = mapper.readValue(json, Settings.class);
        var reserialized = mapper.writeValueAsString(settings);

        assertThat(reserialized)
            .as("unknown key 'model' must survive deserialization+re-serialization — "
                + "requires @JsonAnySetter/@JsonAnyGetter on Settings")
            .contains("\"model\"")
            .contains("claude-opus-4-8");
    }

    @Test
    @DisplayName("multiple unknown Claude Code keys all survive round-trip")
    void multipleUnknownKeys_allPreservedThroughRoundTrip() throws Exception {
        var json = """
            {
              "model": "claude-opus-4-8",
              "statusLine": "auto",
              "outputStyle": "full",
              "cleanupPeriodDays": 30,
              "enabledPlugins": {"java": true}
            }
            """;

        var settings = mapper.readValue(json, Settings.class);
        var reserialized = mapper.writeValueAsString(settings);

        assertThat(reserialized)
            .as("all unknown Claude Code keys must survive round-trip")
            .contains("\"model\"")
            .contains("\"statusLine\"")
            .contains("\"outputStyle\"")
            .contains("\"cleanupPeriodDays\"");
    }

    @Nested
    @DisplayName("when unknown keys are present in settings.json on disk")
    class WhenUnknownKeysOnDisk {

        @Test
        @DisplayName("unknown key survives a read-modify-write cycle via SettingsService")
        void unknownKey_preserved_throughSettingsServiceWriteReadCycle(@TempDir Path tempDir) throws Exception {
            var claudeDir = tempDir.resolve(".claude");
            Files.createDirectories(claudeDir);
            Files.writeString(claudeDir.resolve("settings.json"),
                "{\"model\":\"claude-opus-4-8\",\"enabledPlugins\":{\"java\":true}}");

            var service = new SettingsService();
            var settings = service.read(tempDir);

            // Simulate a mutating operation (adding a plugin) — the unknown key must survive
            var modified = settings.withEnabledPlugins(Map.of("java", true, "typescript", true));
            service.write(modified, tempDir);

            var writtenJson = Files.readString(claudeDir.resolve("settings.json"));
            assertThat(writtenJson)
                .as("unknown key 'model' must survive a read-modify-write cycle through SettingsService — "
                    + "without passthrough, ccx silently destroys Claude Code's own config fields")
                .contains("\"model\"")
                .contains("claude-opus-4-8");
        }
    }
}
