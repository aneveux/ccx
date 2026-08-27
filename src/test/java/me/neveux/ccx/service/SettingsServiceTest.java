package me.neveux.ccx.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import me.neveux.ccx.model.McpServer;
import me.neveux.ccx.model.MarketplaceSource;
import me.neveux.ccx.model.Permissions;
import me.neveux.ccx.model.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SettingsService")
class SettingsServiceTest {

    private SettingsService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new SettingsService();
    }

    @Test
    @DisplayName("should return non-null empty Settings when .claude/settings.json is absent")
    void readReturnsEmptySettingsWhenFileAbsent() {
        var result = service.read(tempDir);

        assertThat(result)
            .as("read must return a non-null Settings even when file is absent")
            .isNotNull();
    }

    @Test
    @DisplayName("should round-trip all fields through write then read")
    void writeAndReadRoundTripsAllFields() throws Exception {
        var settings = buildFullSettings();

        service.write(settings, tempDir);
        var restored = service.read(tempDir);

        assertThat(restored.enabledPlugins())
            .as("enabledPlugins must survive write/read")
            .containsEntry("java@claude-java-stack", true);
        assertThat(restored.env())
            .as("env must survive write/read")
            .containsEntry("ENABLE_LSP_TOOL", "1");
        assertThat(restored.skillOverrides())
            .as("skillOverrides must survive write/read")
            .containsEntry("java-testing", "user-invocable-only");
        assertThat(restored.mcpServers())
            .as("mcpServers must survive write/read")
            .containsKey("my-server");
        assertThat(restored.permissions())
            .as("permissions must survive write/read")
            .isNotNull();
        assertThat(restored.permissions().allow())
            .containsExactly("Bash(mvn *)");
        assertThat(restored.extraKnownMarketplaces())
            .as("extraKnownMarketplaces must survive write/read")
            .hasSize(1);
        assertThat(restored.extraKnownMarketplaces().get(0).key())
            .isEqualTo("my-marketplace");
    }

    @Test
    @DisplayName("should create .claude/settings.json when .claude directory does not exist")
    void writeCreatesClaudeDirectory() throws Exception {
        var settings = new Settings();

        service.write(settings, tempDir);

        assertThat(tempDir.resolve(".claude/settings.json"))
            .as(".claude/settings.json must be created by write")
            .exists();
    }

    @Test
    @DisplayName("should overwrite existing settings.json on second write")
    void writeOverExistingFileOverwritesCorrectly() throws Exception {
        var first = new Settings()
            .withEnabledPlugins(Map.of("plugin-a", true));

        var second = new Settings()
            .withEnabledPlugins(Map.of("plugin-b", true));

        service.write(first, tempDir);
        service.write(second, tempDir);

        var restored = service.read(tempDir);

        assertThat(restored.enabledPlugins())
            .as("second write must win over first write")
            .containsKey("plugin-b")
            .doesNotContainKey("plugin-a");
    }

    @Test
    @DisplayName("should return empty Settings when settings.json is zero bytes")
    void read_returnsEmptySettings_whenFileIsZeroBytes() throws Exception {
        var claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(claudeDir.resolve("settings.json"), "");

        var result = service.read(tempDir);

        assertThat(result)
            .as("zero-byte settings.json must return a non-null Settings (not throw)")
            .isNotNull();
    }

    @Test
    @DisplayName("should leave no .tmp file in the directory after a successful write")
    void write_leavesNoTmpFile_afterSuccess() throws Exception {
        var settings = new Settings()
            .withEnabledPlugins(Map.of("java@claude-java-stack", true));

        service.write(settings, tempDir);

        var claudeDir = tempDir.resolve(".claude");
        try (var stream = Files.list(claudeDir)) {
            var tmpFiles = stream
                .filter(p -> p.getFileName().toString().endsWith(".tmp"))
                .toList();
            assertThat(tmpFiles)
                .as("no .tmp file must remain in .claude/ after a successful write")
                .isEmpty();
        }
    }

    @Test
    @DisplayName("readOrThrow should return empty Settings when settings.json is absent")
    void readOrThrow_returnsEmptySettingsForAbsentFile() throws Exception {
        var result = service.readOrThrow(tempDir);

        assertThat(result)
            .as("readOrThrow must return a non-null Settings when file is absent")
            .isNotNull();
        assertThat(result.enabledPlugins())
            .as("enabledPlugins must be null for an empty Settings")
            .isNull();
    }

    @Test
    @DisplayName("readOrThrow should throw IOException when settings.json is malformed")
    void readOrThrow_throwsIOExceptionForMalformedJson() throws Exception {
        var claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(claudeDir.resolve("settings.json"), "this is not json");

        assertThatThrownBy(() -> service.readOrThrow(tempDir))
            .isInstanceOf(IOException.class);
    }

    @Nested
    @DisplayName("write — atomic-write failure semantics")
    class AtomicWriteFailure {

        // An ObjectWriter whose writeValue(File, ...) always throws, simulating a
        // mid-write serialization failure (disk full, encoding error, etc.).
        // Mockito is not on this project's test classpath, so we use a real subclass.
        private static final class ThrowingWriter extends ObjectWriter {
            ThrowingWriter(ObjectMapper mapper) {
                super(mapper, mapper.getSerializationConfig());
            }

            @Override
            public void writeValue(File resultFile, Object value) throws IOException {
                throw new IOException("simulated serialization failure");
            }
        }

        // A SettingsService backed by a mapper whose pretty-printing writer throws on
        // writeValue, exercising SettingsService.write's IOException -> RuntimeException path.
        private SettingsService failingSerializerService() {
            var mapper = new ObjectMapper() {
                @Override
                public ObjectWriter writerWithDefaultPrettyPrinter() {
                    return new ThrowingWriter(this);
                }
            };
            return new SettingsService(mapper);
        }

        @Test
        @DisplayName("wraps the IOException from the serializer in a RuntimeException")
        void write_when_serializerThrows_then_throwsRuntimeException() throws Exception {
            var failing = failingSerializerService();

            assertThatThrownBy(() -> failing.write(new Settings(), tempDir))
                .as("a serializer IOException must surface as a RuntimeException")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to write settings");
        }

        @Test
        @DisplayName("leaves the pre-existing settings.json byte-for-byte unchanged on failure")
        void write_when_serializerThrows_then_existingFileUnchanged() throws Exception {
            // Establish a known-good settings.json with the real service.
            service.write(new Settings().withEnabledPlugins(Map.of("original-plugin", true)), tempDir);
            var settingsFile = tempDir.resolve(".claude/settings.json");
            var originalBytes = Files.readAllBytes(settingsFile);

            var failing = failingSerializerService();
            assertThatThrownBy(() -> failing.write(new Settings().withEnabledPlugins(Map.of("new-plugin", true)), tempDir))
                .isInstanceOf(RuntimeException.class);

            assertThat(Files.readAllBytes(settingsFile))
                .as("the ATOMIC_MOVE never ran, so the original settings.json must be intact")
                .isEqualTo(originalBytes);
        }
    }

    // -----------------------------------------------------------------------
    // TDD baseline — corrupted settings.json data-loss prevention
    // These tests FAIL until mutating paths (PluginsCommand enable/disable,
    // ProfileApplyService, SkillsService) switch from read() to readOrThrow().
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("corrupted settings.json — data loss prevention (TDD baseline)")
    class WhenSettingsJsonIsCorrupted {

        @Test
        @DisplayName("read() swallows corruption and returns empty Settings — the existing unsafe behaviour")
        void read_returnsEmptySettings_whenFileIsCorrupted() throws Exception {
            var claudeDir = tempDir.resolve(".claude");
            Files.createDirectories(claudeDir);
            Files.writeString(claudeDir.resolve("settings.json"), "{\"broken json");

            // This must KEEP passing — read() intentionally swallows to stay resilient in read-only contexts
            var result = service.read(tempDir);

            assertThat(result)
                .as("read() must return non-null even for corrupted files")
                .isNotNull();
        }

        /**
         * Proves the safety contract that mutating paths MUST use:
         * readOrThrow() on a corrupted file propagates the IOException
         * rather than returning empty settings that would then be written back,
         * silently wiping all the user's Claude Code configuration.
         *
         * This test must KEEP passing — readOrThrow already behaves correctly.
         * Its role here is to anchor the contract: mutating paths that do
         * read → modify → write must call readOrThrow, not read.
         */
        @Test
        @DisplayName("readOrThrow() propagates IOException — mutating paths must use this, not read()")
        void readOrThrow_throwsIOException_preventingSilentDataLoss() throws Exception {
            var claudeDir = tempDir.resolve(".claude");
            Files.createDirectories(claudeDir);
            Files.writeString(claudeDir.resolve("settings.json"), "{\"broken json");

            assertThatThrownBy(() -> service.readOrThrow(tempDir))
                .as("readOrThrow must throw — using this in mutating paths prevents silent settings wipe")
                .isInstanceOf(IOException.class);
        }

        /**
         * The seam every read-modify-write path goes through: it must refuse the update and leave
         * the file exactly as it was, with a message naming the file so the user can fix it.
         */
        @Test
        @DisplayName("readForUpdate refuses a corrupt file, naming it, and leaves the bytes untouched")
        void readForUpdate_corruptSettings_throwsAndLeavesFileUntouched() throws Exception {
            var settingsFile = tempDir.resolve(".claude/settings.json");
            Files.createDirectories(settingsFile.getParent());
            var corruptContent = "{\"model\":\"claude-opus-4-8\",\"enabledPlugins\":{\"java\":true}";
            Files.writeString(settingsFile, corruptContent);

            assertThatThrownBy(() -> service.readForUpdate(tempDir))
                .isInstanceOf(ConfigReadException.class)
                .hasMessageContaining(settingsFile.toString())
                .hasMessageContaining("Refusing to modify");

            assertThat(settingsFile)
                .as("a refused update must not touch the file the user still has to repair")
                .hasContent(corruptContent);
        }

        @Test
        @DisplayName("readForUpdate returns the settings when the file parses")
        void readForUpdate_validSettings_returnsThem() throws Exception {
            var settingsFile = tempDir.resolve(".claude/settings.json");
            Files.createDirectories(settingsFile.getParent());
            Files.writeString(settingsFile, "{\"enabledPlugins\":{\"java@mkt\":true}}");

            var settings = service.readForUpdate(tempDir);

            assertThat(settings.enabledPlugins()).containsEntry("java@mkt", true);
        }
    }

    private Settings buildFullSettings() {
        var mcp = new McpServer("sse", "http://localhost:8080/sse", null, null);
        var permissions = new Permissions(List.of("Bash(mvn *)"), null);
        var source = new MarketplaceSource("my-marketplace", tempDir.toString() + "/marketplace", null, null);

        var settings = new Settings()
            .withEnabledPlugins(Map.of("java@claude-java-stack", true))
            .withEnv(Map.of("ENABLE_LSP_TOOL", "1"))
            .withSkillOverrides(Map.of("java-testing", "user-invocable-only"))
            .withMcpServers(Map.of("my-server", mcp))
            .withPermissions(permissions)
            .withExtraKnownMarketplaces(List.of(source));

        return settings;
    }
}
