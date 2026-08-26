package me.neveux.ccx.service;

import me.neveux.ccx.model.MarketplaceSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClaudeMarketplaceRegistryService")
class ClaudeMarketplaceRegistryServiceTest {

    private static ClaudeMarketplaceRegistryService serviceReading(Path registryFile) {
        return new ClaudeMarketplaceRegistryService() {
            @Override
            protected Path registryFile() {
                return registryFile;
            }
        };
    }

    private static Path writeRegistry(Path directory, String json) throws IOException {
        var file = directory.resolve("known_marketplaces.json");
        Files.writeString(file, json);
        return file;
    }

    @Nested
    @DisplayName("installedMarketplaces()")
    class InstalledMarketplaces {

        @Test
        @DisplayName("returns empty when the registry file does not exist")
        void absentRegistry_returnsEmpty(@TempDir Path tempDir) {
            var service = serviceReading(tempDir.resolve("nope.json"));

            assertThat(service.installedMarketplaces())
                .as("an absent registry must not be treated as an error")
                .isEmpty();
        }

        @Test
        @DisplayName("returns empty when the registry file is malformed rather than throwing")
        void malformedRegistry_returnsEmpty(@TempDir Path tempDir) throws Exception {
            var file = writeRegistry(tempDir, "{ this is not json");
            var service = serviceReading(file);

            assertThat(service.installedMarketplaces())
                .as("a malformed registry must degrade to empty, not fail the command")
                .isEmpty();
        }

        @Test
        @DisplayName("keeps entries whose install location is an existing directory")
        void existingInstallLocation_isKept(@TempDir Path tempDir) throws Exception {
            var installed = Files.createDirectories(tempDir.resolve("marketplaces/my-mkt"));
            var file = writeRegistry(tempDir, """
                {
                  "my-mkt": {
                    "source": {"source": "github", "repo": "owner/private-repo"},
                    "installLocation": "%s",
                    "lastUpdated": "2026-08-26T07:58:34.155Z"
                  }
                }
                """.formatted(installed));
            var service = serviceReading(file);

            assertThat(service.installedMarketplaces())
                .as("an installed marketplace must resolve to its local directory")
                .containsExactly(java.util.Map.entry("my-mkt", installed));
        }

        @Test
        @DisplayName("drops entries whose install location does not exist")
        void missingInstallLocation_isDropped(@TempDir Path tempDir) throws Exception {
            var file = writeRegistry(tempDir, """
                {
                  "gone-mkt": {"installLocation": "%s"}
                }
                """.formatted(tempDir.resolve("never-created")));
            var service = serviceReading(file);

            assertThat(service.installedMarketplaces())
                .as("a recorded install location that no longer exists must be ignored")
                .isEmpty();
        }

        @Test
        @DisplayName("drops entries with a blank or absent install location")
        void blankInstallLocation_isDropped(@TempDir Path tempDir) throws Exception {
            var file = writeRegistry(tempDir, """
                {
                  "blank-mkt": {"installLocation": "   "},
                  "absent-mkt": {"lastUpdated": "2026-08-26T07:58:34.155Z"},
                  "null-mkt": null
                }
                """);
            var service = serviceReading(file);

            assertThat(service.installedMarketplaces())
                .as("entries without a usable install location must be ignored")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("withInstalledLocations()")
    class WithInstalledLocations {

        @Test
        @DisplayName("rewrites a private github source to Claude Code's local clone")
        void githubSourceInRegistry_becomesDirectorySource(@TempDir Path tempDir) throws Exception {
            var installed = Files.createDirectories(tempDir.resolve("marketplaces/cloudbees-marketplace"));
            var file = writeRegistry(tempDir, """
                {
                  "cloudbees-marketplace": {"installLocation": "%s"}
                }
                """.formatted(installed));
            var service = serviceReading(file);
            var declared = new MarketplaceSource(
                "cloudbees-marketplace", null, "github", "cloudbees/cloudbees-claude-plugins");

            assertThat(service.withInstalledLocations(List.of(declared)))
                .as("a github source ccx cannot clone must resolve to Claude Code's clone")
                .containsExactly(new MarketplaceSource(
                    "cloudbees-marketplace", installed.toString(), "directory", null));
        }

        @Test
        @DisplayName("appends marketplaces known only to Claude Code")
        void registryOnlyKey_isAppended(@TempDir Path tempDir) throws Exception {
            var installed = Files.createDirectories(tempDir.resolve("dev"));
            var file = writeRegistry(tempDir, """
                {
                  "cloudbees-dev-marketplace": {"installLocation": "%s"}
                }
                """.formatted(installed));
            var service = serviceReading(file);
            var declared = new MarketplaceSource("other-mkt", "/home/user/other", "directory", null);

            assertThat(service.withInstalledLocations(List.of(declared)))
                .as("a marketplace registered only with Claude Code must still be discoverable")
                .containsExactly(
                    declared,
                    new MarketplaceSource("cloudbees-dev-marketplace", installed.toString(), "directory", null));
        }

        @Test
        @DisplayName("leaves declared sources untouched when the registry does not know them")
        void keyNotInRegistry_isUnchanged(@TempDir Path tempDir) throws Exception {
            var installed = Files.createDirectories(tempDir.resolve("known"));
            var file = writeRegistry(tempDir, """
                {
                  "known-mkt": {"installLocation": "%s"}
                }
                """.formatted(installed));
            var service = serviceReading(file);
            var unknown = new MarketplaceSource("unknown-mkt", null, "github", "owner/public-repo");

            assertThat(service.withInstalledLocations(List.of(unknown)))
                .as("a source Claude Code has not installed must keep its own descriptor")
                .contains(unknown);
        }

        @Test
        @DisplayName("returns the declared sources unchanged when the registry is empty")
        void emptyRegistry_returnsInputUnchanged(@TempDir Path tempDir) {
            var service = serviceReading(tempDir.resolve("nope.json"));
            var declared = List.of(new MarketplaceSource("mkt", "/home/user/mkt", "directory", null));

            assertThat(service.withInstalledLocations(declared))
                .as("no registry means no augmentation")
                .isEqualTo(declared);
        }

        @Test
        @DisplayName("returns an empty list for null sources")
        void nullSources_returnsEmpty(@TempDir Path tempDir) {
            var service = serviceReading(tempDir.resolve("nope.json"));

            assertThat(service.withInstalledLocations(null))
                .as("null sources must not throw")
                .isEmpty();
        }

        @Test
        @DisplayName("preserves declaration order and appends registry-only entries last")
        void ordering_declaredFirstThenRegistryOnly(@TempDir Path tempDir) throws Exception {
            var first = Files.createDirectories(tempDir.resolve("first"));
            var extra = Files.createDirectories(tempDir.resolve("extra"));
            var file = writeRegistry(tempDir, """
                {
                  "b-mkt": {"installLocation": "%s"},
                  "z-mkt": {"installLocation": "%s"}
                }
                """.formatted(first, extra));
            var service = serviceReading(file);
            var declaredA = new MarketplaceSource("a-mkt", "/home/user/a", "directory", null);
            var declaredB = new MarketplaceSource("b-mkt", null, "github", "owner/b");

            assertThat(service.withInstalledLocations(List.of(declaredA, declaredB)))
                .as("declared order must survive augmentation, with registry-only entries last")
                .extracting(MarketplaceSource::key)
                .containsExactly("a-mkt", "b-mkt", "z-mkt");
        }
    }
}
