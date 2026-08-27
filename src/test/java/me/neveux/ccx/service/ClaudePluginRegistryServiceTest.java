package me.neveux.ccx.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClaudePluginRegistryService")
class ClaudePluginRegistryServiceTest {

    private static ClaudePluginRegistryService serviceReading(Path registryFile) {
        return new ClaudePluginRegistryService() {
            @Override
            protected Path registryFile() {
                return registryFile;
            }
        };
    }

    private static Path writeRegistry(Path directory, String json) throws IOException {
        var file = directory.resolve("installed_plugins.json");
        Files.writeString(file, json);
        return file;
    }

    private static Path installTree(Path directory, String name) throws IOException {
        return Files.createDirectories(directory.resolve("cache").resolve(name));
    }

    private static Map<String, Boolean> enabled(String... names) {
        var map = new LinkedHashMap<String, Boolean>();
        for (var name : names) map.put(name, true);
        return map;
    }

    @Nested
    @DisplayName("isInstalledFor()")
    class IsInstalledFor {

        @Test
        @DisplayName("accepts a project-scope record whose projectPath is this project")
        void projectScopeSameProject_isInstalled(@TempDir Path tempDir) throws Exception {
            var project = Files.createDirectories(tempDir.resolve("hivemind"));
            var install = installTree(tempDir, "slack");
            var file = writeRegistry(tempDir, """
                {
                  "version": 2,
                  "plugins": {
                    "slack@claude-plugins-official": [
                      {"scope": "project", "projectPath": "%s", "installPath": "%s", "version": "1.0.0"}
                    ]
                  }
                }
                """.formatted(project, install));

            assertThat(serviceReading(file).isInstalledFor("slack@claude-plugins-official", project))
                .as("a project-scope record for this very project must count as installed")
                .isTrue();
        }

        @Test
        @DisplayName("rejects a project-scope record belonging to another project")
        void projectScopeOtherProject_isNotInstalled(@TempDir Path tempDir) throws Exception {
            var project = Files.createDirectories(tempDir.resolve("hivemind"));
            var other = Files.createDirectories(tempDir.resolve("cbmcp"));
            var install = installTree(tempDir, "cloudbees-context");
            var file = writeRegistry(tempDir, """
                {
                  "version": 2,
                  "plugins": {
                    "cloudbees-context@cloudbees-marketplace": [
                      {"scope": "project", "projectPath": "%s", "installPath": "%s", "version": "1.0.0"}
                    ]
                  }
                }
                """.formatted(other, install));

            assertThat(serviceReading(file).isInstalledFor("cloudbees-context@cloudbees-marketplace", project))
                .as("an install scoped to a different project must not satisfy this one")
                .isFalse();
        }

        @Test
        @DisplayName("accepts a user-scope record regardless of the project")
        void userScope_isInstalledEverywhere(@TempDir Path tempDir) throws Exception {
            var project = Files.createDirectories(tempDir.resolve("anywhere"));
            var install = installTree(tempDir, "claude-stats");
            var file = writeRegistry(tempDir, """
                {
                  "version": 2,
                  "plugins": {
                    "claude-stats@claude-secret-garden": [
                      {"scope": "user", "installPath": "%s", "version": "1.2.0"}
                    ]
                  }
                }
                """.formatted(install));

            assertThat(serviceReading(file).isInstalledFor("claude-stats@claude-secret-garden", project))
                .as("a user-scope install applies to every project")
                .isTrue();
        }

        @Test
        @DisplayName("accepts a managed-scope record regardless of the project")
        void managedScope_isInstalledEverywhere(@TempDir Path tempDir) throws Exception {
            var project = Files.createDirectories(tempDir.resolve("anywhere"));
            var install = installTree(tempDir, "corp-plugin");
            var file = writeRegistry(tempDir, """
                {
                  "version": 2,
                  "plugins": {
                    "corp-plugin@corp-marketplace": [
                      {"scope": "managed", "installPath": "%s", "version": "3.0.0"}
                    ]
                  }
                }
                """.formatted(install));

            assertThat(serviceReading(file).isInstalledFor("corp-plugin@corp-marketplace", project))
                .as("a managed-scope install is enterprise-wide and applies everywhere")
                .isTrue();
        }

        @Test
        @DisplayName("accepts a local-scope record whose projectPath is this project")
        void localScopeSameProject_isInstalled(@TempDir Path tempDir) throws Exception {
            var project = Files.createDirectories(tempDir.resolve("hivemind"));
            var install = installTree(tempDir, "slack");
            var file = writeRegistry(tempDir, """
                {
                  "version": 2,
                  "plugins": {
                    "slack@claude-plugins-official": [
                      {"scope": "local", "projectPath": "%s", "installPath": "%s", "version": "1.0.0"}
                    ]
                  }
                }
                """.formatted(project, install));

            assertThat(serviceReading(file).isInstalledFor("slack@claude-plugins-official", project))
                .as("local scope is project-bound like project scope")
                .isTrue();
        }

        @Test
        @DisplayName("finds a matching record among several scopes for the same plugin")
        void multipleRecords_matchWins(@TempDir Path tempDir) throws Exception {
            var project = Files.createDirectories(tempDir.resolve("hivemind"));
            var other = Files.createDirectories(tempDir.resolve("hacker"));
            var install = installTree(tempDir, "slack");
            var file = writeRegistry(tempDir, """
                {
                  "version": 2,
                  "plugins": {
                    "slack@claude-plugins-official": [
                      {"scope": "local", "projectPath": "%s", "installPath": "%s", "version": "1.0.0"},
                      {"scope": "project", "projectPath": "%s", "installPath": "%s", "version": "1.0.0"}
                    ]
                  }
                }
                """.formatted(other, install, project, install));

            assertThat(serviceReading(file).isInstalledFor("slack@claude-plugins-official", project))
                .as("one satisfying record among many is enough")
                .isTrue();
        }

        @Test
        @DisplayName("rejects a record whose installPath no longer exists")
        void missingInstallPath_isNotInstalled(@TempDir Path tempDir) throws Exception {
            var project = Files.createDirectories(tempDir.resolve("hivemind"));
            var file = writeRegistry(tempDir, """
                {
                  "version": 2,
                  "plugins": {
                    "ghost@mkt": [
                      {"scope": "project", "projectPath": "%s", "installPath": "%s", "version": "1.0.0"}
                    ]
                  }
                }
                """.formatted(project, tempDir.resolve("cache/never-created")));

            assertThat(serviceReading(file).isInstalledFor("ghost@mkt", project))
                .as("a record pointing at a vanished cache directory is not usable")
                .isFalse();
        }

        @Test
        @DisplayName("rejects a record whose install tree carries an .orphaned_at marker")
        void orphanedInstallPath_isNotInstalled(@TempDir Path tempDir) throws Exception {
            var project = Files.createDirectories(tempDir.resolve("hivemind"));
            var install = installTree(tempDir, "cloudbees-context");
            Files.writeString(install.resolve(".orphaned_at"), "2026-08-20T10:00:00.000Z");
            var file = writeRegistry(tempDir, """
                {
                  "version": 2,
                  "plugins": {
                    "cloudbees-context@cloudbees-marketplace": [
                      {"scope": "project", "projectPath": "%s", "installPath": "%s", "version": "2a1dd9dac1c6"}
                    ]
                  }
                }
                """.formatted(project, install));

            assertThat(serviceReading(file).isInstalledFor("cloudbees-context@cloudbees-marketplace", project))
                .as("an install tree marked for garbage collection must be treated as missing")
                .isFalse();
        }

        @Test
        @DisplayName("rejects a plugin absent from the registry")
        void unknownPlugin_isNotInstalled(@TempDir Path tempDir) throws Exception {
            var project = Files.createDirectories(tempDir.resolve("hivemind"));
            var file = writeRegistry(tempDir, """
                {"version": 2, "plugins": {}}
                """);

            assertThat(serviceReading(file).isInstalledFor("nothing@mkt", project))
                .as("an unlisted plugin is not installed")
                .isFalse();
        }

        @Test
        @DisplayName("reports not installed when the registry file does not exist")
        void absentRegistry_isNotInstalled(@TempDir Path tempDir) {
            var service = serviceReading(tempDir.resolve("nope.json"));

            assertThat(service.isInstalledFor("slack@claude-plugins-official", tempDir))
                .as("no registry means nothing has ever been installed")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("missingInstalls()")
    class MissingInstalls {

        @Test
        @DisplayName("reports only the enabled plugins without a usable record, in declaration order")
        void mixedState_reportsMissingInOrder(@TempDir Path tempDir) throws Exception {
            var project = Files.createDirectories(tempDir.resolve("hivemind"));
            var other = Files.createDirectories(tempDir.resolve("cbmcp"));
            var statsInstall = installTree(tempDir, "claude-stats");
            var contextInstall = installTree(tempDir, "cloudbees-context");
            var file = writeRegistry(tempDir, """
                {
                  "version": 2,
                  "plugins": {
                    "claude-stats@claude-secret-garden": [
                      {"scope": "project", "projectPath": "%s", "installPath": "%s", "version": "1.0.0"}
                    ],
                    "cloudbees-context@cloudbees-marketplace": [
                      {"scope": "project", "projectPath": "%s", "installPath": "%s", "version": "1.0.0"}
                    ]
                  }
                }
                """.formatted(project, statsInstall, other, contextInstall));

            var settings = enabled(
                "cloudbees-context@cloudbees-marketplace",
                "claude-stats@claude-secret-garden",
                "slack@claude-plugins-official");

            assertThat(serviceReading(file).missingInstalls(settings, project))
                .as("only the plugins lacking a record for this project must be reported, in order")
                .containsExactly("cloudbees-context@cloudbees-marketplace", "slack@claude-plugins-official");
        }

        @Test
        @DisplayName("ignores plugins explicitly disabled in settings")
        void disabledPlugins_areIgnored(@TempDir Path tempDir) throws Exception {
            var project = Files.createDirectories(tempDir.resolve("hivemind"));
            var file = writeRegistry(tempDir, """
                {"version": 2, "plugins": {}}
                """);
            var settings = new LinkedHashMap<String, Boolean>();
            settings.put("off@mkt", false);
            settings.put("null@mkt", null);
            settings.put("on@mkt", true);

            assertThat(serviceReading(file).missingInstalls(settings, project))
                .as("a plugin that is not enabled needs no install")
                .containsExactly("on@mkt");
        }

        @Test
        @DisplayName("reports every enabled plugin when the registry file is absent")
        void absentRegistry_reportsAllEnabled(@TempDir Path tempDir) {
            var service = serviceReading(tempDir.resolve("nope.json"));

            assertThat(service.missingInstalls(enabled("a@mkt", "b@mkt"), tempDir))
                .as("with no registry, nothing is installed, so everything enabled is missing")
                .containsExactly("a@mkt", "b@mkt");
        }

        @Test
        @DisplayName("reports nothing when the registry is malformed rather than a wall of false positives")
        void malformedRegistry_reportsNothing(@TempDir Path tempDir) throws Exception {
            var file = writeRegistry(tempDir, "{ this is not json");

            assertThat(serviceReading(file).missingInstalls(enabled("a@mkt", "b@mkt"), tempDir))
                .as("an unreadable registry is advisory: report nothing rather than flag everything")
                .isEmpty();
        }

        @Test
        @DisplayName("returns empty for null or empty enabled plugins")
        void noEnabledPlugins_returnsEmpty(@TempDir Path tempDir) {
            var service = serviceReading(tempDir.resolve("nope.json"));

            assertThat(service.missingInstalls(null, tempDir))
                .as("null enabled plugins must not throw")
                .isEmpty();
            assertThat(service.missingInstalls(Map.of(), tempDir))
                .as("no enabled plugins means nothing missing")
                .isEmpty();
        }
    }
}
