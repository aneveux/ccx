package me.neveux.ccx.service;

import me.neveux.ccx.model.CcxContext;
import me.neveux.ccx.model.ProfileDriftState;
import me.neveux.ccx.model.Settings;
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

@DisplayName("CcxContextService")
class CcxContextServiceTest {

    private CcxContextService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new CcxContextService();
    }

    // -----------------------------------------------------------------------
    // read()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("read()")
    class Read {

        @Test
        @DisplayName("should return empty context when .ccx/context.json is absent")
        void read_returnsEmptyContextWhenFileAbsent() {
            var result = service.read(tempDir);

            assertThat(result)
                .as("read must return a non-null context even when file is absent")
                .isNotNull();
            assertThat(result.version())
                .as("absent file must yield version 1")
                .isEqualTo("1");
            assertThat(result.enabledProfiles())
                .as("absent file must yield empty enabledProfiles map")
                .isEmpty();
        }

        @Test
        @DisplayName("should parse structure correctly from a valid context.json")
        void read_parsesStructureFromValidFile() throws Exception {
            var ccxDir = tempDir.resolve(".ccx");
            Files.createDirectories(ccxDir);
            var contextFile = ccxDir.resolve("context.json");

            var json = """
                {
                  "version": "1",
                  "enabledProfiles": {
                    "java": {
                      "enabledAt": "2024-01-15T10:00:00Z",
                      "snapshot": {
                        "plugins": ["java@claude-java-stack"],
                        "stacks": ["java"],
                        "rules": ["java"],
                        "permissionsAllow": ["Bash(mvn *)"],
                        "permissionsDeny": []
                      }
                    }
                  }
                }
                """;
            Files.writeString(contextFile, json);

            var result = service.read(tempDir);

            assertThat(result.version())
                .as("version must be parsed from file")
                .isEqualTo("1");
            assertThat(result.enabledProfiles())
                .as("enabledProfiles must contain the java profile")
                .containsKey("java");
            var profile = result.enabledProfiles().get("java");
            assertThat(profile.snapshot().plugins())
                .as("snapshot plugins must be parsed correctly")
                .containsExactly("java@claude-java-stack");
            assertThat(profile.snapshot().rules())
                .as("snapshot rules must be parsed correctly")
                .containsExactly("java");
        }
    }

    // -----------------------------------------------------------------------
    // write()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("write()")
    class Write {

        @Test
        @DisplayName("should create .ccx/context.json atomically")
        void write_createsContextJsonFile() throws Exception {
            var ctx = new CcxContext("1", Map.of());

            service.write(ctx, tempDir);

            assertThat(tempDir.resolve(".ccx/context.json"))
                .as(".ccx/context.json must exist after write")
                .exists();
        }
    }

    // -----------------------------------------------------------------------
    // recordEnabled() / recordDisabled()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("recordEnabled()")
    class RecordEnabled {

        @Test
        @DisplayName("should add profile to context and persist it")
        void recordEnabled_addsProfileAndPersists() throws Exception {
            var snapshot = new CcxContext.ProfileSnapshot(
                List.of("java@claude-java-stack"),
                List.of("java"),
                List.of("Bash(mvn *)"),
                List.of(),
                null, null, null, null
            );

            service.recordEnabled("java", snapshot, tempDir);

            var result = service.read(tempDir);
            assertThat(result.enabledProfiles())
                .as("enabledProfiles must contain the recorded profile")
                .containsKey("java");
            assertThat(result.enabledProfiles().get("java").snapshot().plugins())
                .as("persisted snapshot must include the recorded plugins")
                .containsExactly("java@claude-java-stack");
        }
    }

    @Nested
    @DisplayName("recordDisabled()")
    class RecordDisabled {

        @Test
        @DisplayName("should remove profile from context after it was enabled")
        void recordDisabled_removesProfileFromContext() throws Exception {
            var snapshot = new CcxContext.ProfileSnapshot(
                List.of("java@claude-java-stack"),
                List.of("java"),
                List.of("java"),
                List.of(),
                List.of(),
                null, null, null
            );
            service.recordEnabled("java", snapshot, tempDir);

            service.recordDisabled("java", tempDir);

            var result = service.read(tempDir);
            assertThat(result.enabledProfiles())
                .as("enabledProfiles must not contain the disabled profile")
                .doesNotContainKey("java");
        }
    }

    // -----------------------------------------------------------------------
    // multi-profile lifecycle
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("multi-profile lifecycle")
    class MultiProfileLifecycle {

        @Test
        @DisplayName("should retain quarkus profile after disabling java when both were enabled")
        void multiProfileLifecycle_recordTwiceDisableOne() throws Exception {
            var javaSnapshot = new CcxContext.ProfileSnapshot(
                List.of("java@claude-java-stack"),
                List.of("java"),
                List.of(),
                List.of(),
                null, null, null, null
            );
            var quarkusSnapshot = new CcxContext.ProfileSnapshot(
                List.of("quarkus@claude-java-stack"),
                List.of(),
                List.of(),
                List.of(),
                null, null, null, null
            );

            service.recordEnabled("java", javaSnapshot, tempDir);
            service.recordEnabled("quarkus", quarkusSnapshot, tempDir);
            service.recordDisabled("java", tempDir);

            var result = service.read(tempDir);
            assertThat(result.enabledProfiles())
                .as("quarkus must still be enabled after disabling java")
                .containsKey("quarkus");
            assertThat(result.enabledProfiles())
                .as("java must be removed after disabling it")
                .doesNotContainKey("java");
        }
    }

    // -----------------------------------------------------------------------
    // detectDrift()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("detectDrift()")
    class DetectDrift {

        private Settings settingsWithPlugin(String pluginName, boolean enabled) {
            var settings = new Settings()
                .withEnabledPlugins(Map.of(pluginName, enabled));
            return settings;
        }

        @Test
        @DisplayName("should return DISABLED when recorded snapshot is null")
        void detectDrift_returnsDisabledWhenSnapshotIsNull() throws Exception {
            var claudeDir = tempDir.resolve(".claude");
            Files.createDirectories(claudeDir);
            var settings = new Settings();

            var result = service.detectDrift("missing-profile", null, settings, claudeDir);

            assertThat(result)
                .as("detectDrift must return DISABLED when snapshot is null")
                .isEqualTo(ProfileDriftState.DISABLED);
        }

        @Test
        @DisplayName("should return ALTERED when a plugin is missing from liveSettings")
        void detectDrift_returnsAlteredWhenPluginMissing() throws Exception {
            var claudeDir = tempDir.resolve(".claude");
            Files.createDirectories(claudeDir);

            var settings = new Settings()
                .withEnabledPlugins(Map.of());
            var snapshot = new CcxContext.ProfileSnapshot(
                List.of("java@claude-java-stack"),
                List.of(),
                List.of(),
                List.of(),
                null, null, null, null
            );

            var result = service.detectDrift("java", snapshot, settings, claudeDir);

            assertThat(result)
                .as("detectDrift must return ALTERED when a plugin in snapshot is absent from liveSettings")
                .isEqualTo(ProfileDriftState.ALTERED);
        }

        @Test
        @DisplayName("should return ENABLED when plugin key with @ is present in settings")
        void detectDrift_returnsEnabled_whenAllPluginsPresent() throws Exception {
            var claudeDir = tempDir.resolve(".claude");
            Files.createDirectories(claudeDir);
            Files.writeString(claudeDir.resolve("CLAUDE.md"), "<!-- ccx:begin -->\n<!-- ccx:end -->\n");

            var settings = new Settings()
                .withEnabledPlugins(Map.of("test-plugin@test", true));
            var snapshot = new CcxContext.ProfileSnapshot(
                List.of("test-plugin@test"),
                List.of(),
                List.of(),
                List.of(),
                null, null, null, null
            );

            var result = service.detectDrift("test", snapshot, settings, claudeDir);

            assertThat(result)
                .as("detectDrift must return ENABLED when all plugins are present in settings")
                .isEqualTo(ProfileDriftState.ENABLED);
        }

        @Test
        @DisplayName("should return ALTERED when a rule file is missing from rules/ directory")
        void detectDrift_returnsAltered_whenRuleFileMissing() throws Exception {
            var claudeDir = tempDir.resolve(".claude");
            Files.createDirectories(claudeDir);

            var settings = new Settings();
            var snapshot = new CcxContext.ProfileSnapshot(
                List.of(),
                List.of("my-rule"),
                List.of(),
                List.of(),
                null, null, null, null
            );
            // Do NOT create the rule file

            var result = service.detectDrift("test", snapshot, settings, claudeDir);

            assertThat(result)
                .as("detectDrift must return ALTERED when a rule file is missing from disk")
                .isEqualTo(ProfileDriftState.ALTERED);
        }

        @Test
        @DisplayName("should return ALTERED when a fragment is missing from .claude/CLAUDE.md marker block")
        void detectDrift_returnsAltered_whenFragmentMissingFromPrivateClaudeMd() throws Exception {
            var claudeDir = tempDir.resolve(".claude");
            Files.createDirectories(claudeDir);
            Files.writeString(claudeDir.resolve("CLAUDE.md"), "<!-- ccx:begin -->\n<!-- ccx:end -->\n");

            var settings = new Settings();
            var snapshot = new CcxContext.ProfileSnapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null, List.of("/abs/frag.md"), null, null
            );

            var result = service.detectDrift("test", snapshot, settings, claudeDir);

            assertThat(result)
                .as("detectDrift must return ALTERED when fragment is not in the ccx marker block")
                .isEqualTo(ProfileDriftState.ALTERED);
        }

        @Test
        @DisplayName("should return ALTERED when plugin key is mapped to false in enabledPlugins")
        void detectDrift_returnsAltered_whenPluginKeyMappedToFalse() throws Exception {
            var claudeDir = tempDir.resolve(".claude");
            Files.createDirectories(claudeDir);

            var settings = new Settings()
                .withEnabledPlugins(Map.of("java:java-developer", false));
            var snapshot = new CcxContext.ProfileSnapshot(
                List.of("java:java-developer"),
                List.of(),
                List.of(),
                List.of(),
                null, null, null, null
            );

            var result = service.detectDrift("java", snapshot, settings, claudeDir, List.of());

            assertThat(result)
                .as("detectDrift must return ALTERED when plugin is mapped to false (not Boolean.TRUE)")
                .isEqualTo(ProfileDriftState.ALTERED);
        }
    }

    // -----------------------------------------------------------------------
    // read() — corrupt context.json
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("read() with corrupt context.json")
    class ReadCorrupt {

        @Test
        @DisplayName("should return empty context without throwing when context.json is corrupt")
        void read_returnsEmptyContext_whenContextJsonIsCorrupt() throws Exception {
            var ccxDir = tempDir.resolve(".ccx");
            Files.createDirectories(ccxDir);
            Files.writeString(ccxDir.resolve("context.json"), "not valid json");

            var result = service.read(tempDir);

            assertThat(result)
                .as("read must return a non-null context even when file is corrupt")
                .isNotNull();
            assertThat(result.enabledProfiles())
                .as("corrupt context.json must yield empty enabledProfiles map")
                .isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // TDD baseline — corrupted context.json data-loss prevention
    // These tests FAIL until recordEnabled() and recordDisabled() switch
    // from read() to a readOrThrow()-based approach.
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("recordEnabled/recordDisabled — corruption guard (TDD baseline)")
    class WhenContextJsonIsCorrupted {

        /**
         * recordEnabled() currently calls read() which swallows parse errors and returns
         * an empty CcxContext. It then adds the new profile to that empty context and writes
         * it back — silently destroying all previously recorded profiles.
         *
         * After the fix, recordEnabled() must call readOrThrow() (or equivalent) so that a
         * corrupted context.json causes an IOException rather than a silent overwrite.
         *
         * This test FAILS until the production code is changed.
         */
        @Test
        @DisplayName("recordEnabled() throws when context.json is corrupted — does not silently overwrite")
        void recordEnabled_throwsIOException_whenContextJsonIsCorrupted() throws Exception {
            var ccxDir = tempDir.resolve(".ccx");
            Files.createDirectories(ccxDir);
            Files.writeString(ccxDir.resolve("context.json"), "{corrupted json");

            var snapshot = new CcxContext.ProfileSnapshot(
                java.util.List.of("java@claude-java-stack"),
                java.util.List.of("java"),
                java.util.List.of(),
                java.util.List.of(),
                null, null, null, null
            );

            org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.recordEnabled("java", snapshot, tempDir))
                .as("recordEnabled must propagate IOException on corrupted context.json — "
                    + "currently FAILS because it calls read() which swallows the error")
                .isInstanceOf(java.io.IOException.class);
        }

        /**
         * Same contract for recordDisabled(): silently reading a corrupted context.json as
         * empty means the subsequent write removes no profiles (the map is already empty)
         * but still overwrites the corrupted file with a blank context, destroying data.
         *
         * This test FAILS until the production code is changed.
         */
        @Test
        @DisplayName("recordDisabled() throws when context.json is corrupted — does not silently overwrite")
        void recordDisabled_throwsIOException_whenContextJsonIsCorrupted() throws Exception {
            var ccxDir = tempDir.resolve(".ccx");
            Files.createDirectories(ccxDir);
            Files.writeString(ccxDir.resolve("context.json"), "{corrupted json");

            org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.recordDisabled("java", tempDir))
                .as("recordDisabled must propagate IOException on corrupted context.json — "
                    + "currently FAILS because it calls read() which swallows the error")
                .isInstanceOf(java.io.IOException.class);
        }
    }

    // -----------------------------------------------------------------------
    // ProfileSnapshot — new fields
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ProfileSnapshot — new fields")
    class ProfileSnapshotNewFields {

        @Test
        @DisplayName("new fields round-trip through JSON write/read")
        void profileSnapshot_newFields_roundTripThroughJson() throws Exception {
            var snapshot = new CcxContext.ProfileSnapshot(
                List.of("java@cjs"), List.of("java"),
                List.of(), List.of(),
                List.of("review"), List.of("/home/user/.config/ccx/profiles/java/fragments/java.md"),
                Map.of("ENABLE_LSP_TOOL", "1"), List.of("target/")
            );
            service.recordEnabled("java", snapshot, tempDir);
            var result = service.read(tempDir);
            var loaded = result.enabledProfiles().get("java").snapshot();
            assertThat(loaded.commands()).containsExactly("review");
            assertThat(loaded.fragments()).containsExactly("/home/user/.config/ccx/profiles/java/fragments/java.md");
            assertThat(loaded.env()).containsEntry("ENABLE_LSP_TOOL", "1");
            assertThat(loaded.ignorePatterns()).containsExactly("target/");
        }

        @Test
        @DisplayName("old context.json without new fields deserializes with null values")
        void profileSnapshot_oldJsonWithoutNewFields_deserializesWithNulls() throws Exception {
            var ccxDir = tempDir.resolve(".ccx");
            Files.createDirectories(ccxDir);
            Files.writeString(ccxDir.resolve("context.json"), """
                {"version":"1","enabledProfiles":{"java":{"enabledAt":"2024-01-15T10:00:00Z",
                "snapshot":{"plugins":["java@cjs"],"stacks":["java"],"rules":["java"],
                "permissionsAllow":[],"permissionsDeny":[]}}}}
                """);
            var result = service.read(tempDir);
            var snapshot = result.enabledProfiles().get("java").snapshot();
            assertThat(snapshot.commands()).isNull();
            assertThat(snapshot.fragments()).isNull();
            assertThat(snapshot.env()).isNull();
            assertThat(snapshot.ignorePatterns()).isNull();
        }
    }
}
