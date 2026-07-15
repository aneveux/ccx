package me.neveux.ccx.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CcxConfigService — config directory resolution")
class CcxConfigServiceTest {

    // Mirrors the env-based resolution in CcxConfigService.configDir() (no override).
    // The service reads System.getenv directly with no seam, so we recompute the
    // expected value the same way the code does and assert equality — this exercises
    // whichever branch (XDG vs user.home) the test JVM is actually in, deterministically.
    private static Path expectedEnvConfigDir() {
        var xdg = System.getenv("XDG_CONFIG_HOME");
        var base = (xdg != null && !xdg.isBlank())
            ? Path.of(xdg)
            : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("ccx");
    }

    @Nested
    @DisplayName("configDir()")
    class ConfigDir {

        @Test
        @DisplayName("override wins over any environment when supplied to the constructor")
        void configDir_when_overrideSupplied_then_returnsOverride(@TempDir Path tempDir) {
            assertThat(new CcxConfigService(tempDir).configDir())
                .as("the constructor override must win regardless of XDG_CONFIG_HOME / user.home")
                .isEqualTo(tempDir);
        }

        @Test
        @DisplayName("no override resolves the env/home path exactly as the code computes it")
        void configDir_when_noOverride_then_matchesEnvDerivedPath() {
            assertThat(new CcxConfigService().configDir())
                .as("env path must match XDG/ccx when XDG_CONFIG_HOME is set, else user.home/.config/ccx")
                .isEqualTo(expectedEnvConfigDir());
        }

        @Test
        @DisplayName("no override always ends with the 'ccx' segment (xdg/ccx or .config/ccx)")
        void configDir_when_noOverride_then_endsWithCcx() {
            var dir = new CcxConfigService().configDir();

            assertThat(dir.getFileName().toString())
                .as("the final path segment must be 'ccx'")
                .isEqualTo("ccx");

            var xdg = System.getenv("XDG_CONFIG_HOME");
            if (xdg == null || xdg.isBlank()) {
                assertThat(dir)
                    .as("fallback branch puts the config under <home>/.config/ccx")
                    .endsWith(Path.of(".config", "ccx"));
            } else {
                assertThat(dir)
                    .as("XDG branch puts the config under <xdg>/ccx")
                    .isEqualTo(Path.of(xdg).resolve("ccx"));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Plan 040 Item 12 — profileDir name validation (RED until guard added)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("profileDir() — name validation")
    class ProfileDirValidation {

        @Test
        @DisplayName("rejects a traversal name (../ prefix) with IllegalArgumentException")
        void profileDir_rejectsTraversal(@TempDir Path tempDir) {
            assertThatThrownBy(() -> new CcxConfigService(tempDir).profileDir("../evil"))
                .as("profileDir must reject path-traversal names with IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("accepts a valid profile name and returns a path under the base dir")
        void profileDir_acceptsValidName(@TempDir Path tempDir) {
            var result = new CcxConfigService(tempDir).profileDir("java");

            assertThat(result.toString())
                .as("profileDir(valid) must return a path under the config base directory")
                .startsWith(tempDir.toString());
            assertThat(result.toString())
                .as("profileDir(valid) must include the name in the path")
                .contains("java");
        }

        @ParameterizedTest(name = "profileDir rejects \"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = {"UPPER", "has space", "a/b", ".hidden", "name..x"})
        @DisplayName("rejects invalid names with IllegalArgumentException")
        void profileDir_rejectsInvalidNames(String name, @TempDir Path tempDir) {
            assertThatThrownBy(() -> new CcxConfigService(tempDir).profileDir(name))
                .as("profileDir must reject invalid name: " + name)
                .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "profileDir accepts \"{0}\"")
        @ValueSource(strings = {"valid-name", "java", "my-profile-123", "go"})
        @DisplayName("accepts valid names and returns a path under the base dir")
        void profileDir_acceptsValidNames(String name, @TempDir Path tempDir) {
            var result = new CcxConfigService(tempDir).profileDir(name);
            assertThat(result.toString())
                .as("profileDir must return a path containing the name")
                .contains(name);
        }
    }

    @Nested
    @DisplayName("derived directories (with an override base)")
    class Derivations {

        @Test
        @DisplayName("profilesDir / profileDir / claudemd dirs derive from the override base")
        void derivations_when_overrideBase_then_resolveUnderIt(@TempDir Path tempDir) {
            var service = new CcxConfigService(tempDir);

            assertThat(service.profilesDir())
                .as("profilesDir == <base>/profiles")
                .isEqualTo(tempDir.resolve("profiles"));
            assertThat(service.profileDir("java"))
                .as("profileDir(name) == <base>/profiles/<name>")
                .isEqualTo(tempDir.resolve("profiles").resolve("java"));
            assertThat(service.globalClaudeMdDir())
                .as("globalClaudeMdDir == <base>/claudemd")
                .isEqualTo(tempDir.resolve("claudemd"));
            assertThat(service.profileClaudeMdDir("java"))
                .as("profileClaudeMdDir(name) == <base>/profiles/<name>/claudemd")
                .isEqualTo(tempDir.resolve("profiles").resolve("java").resolve("claudemd"));
        }
    }
}
