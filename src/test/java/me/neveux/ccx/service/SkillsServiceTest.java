package me.neveux.ccx.service;

import me.neveux.ccx.model.MarketplaceSource;
import me.neveux.ccx.model.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SkillsService")
class SkillsServiceTest {

    private SkillsService service;
    private SettingsService settingsService;

    @TempDir
    Path tempDir;

    private Path registryFile;

    @BeforeEach
    void setUp() {
        settingsService = new SettingsService();
        registryFile = tempDir.resolve("known_marketplaces.json");
        service = new SkillsService(settingsService, new ClaudeMarketplaceRegistryService() {
            @Override
            protected Path registryFile() {
                return registryFile;
            }
        });
    }

    // -----------------------------------------------------------------------
    // discoverSkills()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("discoverSkills")
    class DiscoverSkills {

        @Test
        @DisplayName("should return SkillEntry for each SKILL.md found in marketplace directories")
        void discoverSkills_returnsSkillsFromMarketplace() throws Exception {
            var marketplaceDir = tempDir.resolve("marketplace");
            Files.createDirectories(marketplaceDir.resolve("java/skills/java"));
            Files.createFile(marketplaceDir.resolve("java/skills/java/SKILL.md"));

            var settings = settingsWithMarketplace(marketplaceDir);

            var results = service.discoverSkills(settings, new Settings());

            assertThat(results)
                .as("one SKILL.md on disk must yield exactly one SkillEntry")
                .hasSize(1);

            var entry = results.get(0);
            assertThat(entry.skillId())
                .as("skillId must be pluginName:skillName")
                .isEqualTo("java:java");
            assertThat(entry.pluginName())
                .as("pluginName must be the plugin directory name")
                .isEqualTo("java");
            assertThat(entry.skillName())
                .as("skillName must be the skill directory name")
                .isEqualTo("java");
            assertThat(entry.skillMdPath())
                .as("skillMdPath must be the absolute path to SKILL.md")
                .isAbsolute()
                .hasFileName("SKILL.md")
                .exists();
        }

        @Test
        @DisplayName("should not return a SkillEntry when the skill directory has no SKILL.md")
        void discoverSkills_ignoresDirWithoutSkillMd() throws Exception {
            var marketplaceDir = tempDir.resolve("marketplace");
            // skill dir exists but SKILL.md is absent
            Files.createDirectories(marketplaceDir.resolve("java/skills/java"));

            var settings = settingsWithMarketplace(marketplaceDir);

            var results = service.discoverSkills(settings, new Settings());

            assertThat(results)
                .as("skill directory without SKILL.md must be ignored")
                .isEmpty();
        }

        @Test
        @DisplayName("should return empty list when settings has no marketplace sources")
        void discoverSkills_emptyWhenNoMarketplaces() {
            var settings = new Settings()
                .withExtraKnownMarketplaces(null);

            var results = service.discoverSkills(settings, new Settings());

            assertThat(results)
                .as("null extraKnownMarketplaces must yield empty result")
                .isNotNull()
                .isEmpty();
        }

        @Test
        @EnabledOnOs(OS.LINUX)
        @DisplayName("should reject a symlink whose real target is OUTSIDE all allowed roots (traversal guard)")
        void discoverSkills_rejectsSymlinkResolvingOutsideAllowedRoots() throws Exception {
            // A symlink under tempDir (allowed) pointing at /etc (outside home/tmp/cwd).
            // discoverSkills first stats the dir, then PathSecurity.resolveAndCheck calls
            // toRealPath() which resolves the symlink to /etc and rejects it.
            var link = tempDir.resolve("escape-link");
            java.nio.file.Files.createSymbolicLink(link, Path.of("/etc"));

            // Direct, unambiguous boundary assertion on the guard itself.
            assertThat(PathSecurity.resolveAndCheck(link))
                .as("a symlink resolving to /etc must be rejected by the traversal guard")
                .isFalse();

            var source = new MarketplaceSource("escape", link.toString(), null, null);
            var settings = new Settings().withExtraKnownMarketplaces(List.of(source));

            assertThat(service.discoverSkills(settings, new Settings()))
                .as("a marketplace source whose real path escapes the allowed roots must yield no skills")
                .isEmpty();
        }

        @Test
        @EnabledOnOs(OS.LINUX)
        @DisplayName("should ACCEPT a symlink whose real target is INSIDE an allowed root (proves it is not blanket-rejecting symlinks)")
        void discoverSkills_acceptsSymlinkResolvingInsideAllowedRoots() throws Exception {
            // Real plugin structure inside tempDir (an allowed root, under java.io.tmpdir).
            var realMarketplace = tempDir.resolve("real-marketplace");
            Files.createDirectories(realMarketplace.resolve("java/skills/java"));
            Files.createFile(realMarketplace.resolve("java/skills/java/SKILL.md"));

            // Symlink, also under tempDir, pointing at that real (allowed) marketplace.
            var link = tempDir.resolve("good-link");
            java.nio.file.Files.createSymbolicLink(link, realMarketplace);

            // The guard must ACCEPT this symlink — its real path is inside an allowed root.
            assertThat(PathSecurity.resolveAndCheck(link))
                .as("a symlink resolving inside an allowed root must be accepted by the guard")
                .isTrue();

            var source = new MarketplaceSource("good", link.toString(), null, null);
            var settings = new Settings().withExtraKnownMarketplaces(List.of(source));

            assertThat(service.discoverSkills(settings, new Settings()))
                .as("a symlinked-but-allowed marketplace must still yield its skill — proving the negative test is not vacuous")
                .extracting(SkillsService.SkillEntry::skillId)
                .containsExactly("java:java");
        }

        // -------------------------------------------------------------------
        // github source with null path must not NPE; must skip
        // -------------------------------------------------------------------

        @Test
        @DisplayName("should not throw NPE when a github source has a null path and must skip it")
        void discoverSkills_skipsGithubSourceWithNullPath() {
            var githubSource = new MarketplaceSource("remote-mkt", null, "github", "owner/repo");

            var settings = new Settings()
                .withExtraKnownMarketplaces(List.of(githubSource));

            // PluginMapService resolves github sources via localCacheDir(key) and never
            // calls Path.of(source.path) directly. SkillsService must do the same — a null
            // path on a github source must not blow up with NullPointerException.
            assertThat(service.discoverSkills(settings, new Settings()))
                .as("github source with null path must be skipped, not crash with NPE")
                .isNotNull()
                .isEmpty();
        }

        @Test
        @DisplayName("should skip a github null-path source but still return skills from a valid directory source")
        void discoverSkills_skipsNullPathGithubButKeepsValidDirectory() throws Exception {
            var marketplaceDir = tempDir.resolve("marketplace");
            Files.createDirectories(marketplaceDir.resolve("java/skills/java"));
            Files.createFile(marketplaceDir.resolve("java/skills/java/SKILL.md"));

            var githubSource = new MarketplaceSource("remote-mkt", null, "github", "owner/repo");

            var dirSource = new MarketplaceSource("local-mkt", marketplaceDir.toString(), null, null);

            var settings = new Settings()
                .withExtraKnownMarketplaces(List.of(githubSource, dirSource));

            var results = service.discoverSkills(settings, new Settings());

            assertThat(results)
                .as("null-path github source must be skipped; the valid directory source must still yield its skill")
                .extracting(SkillsService.SkillEntry::skillId)
                .containsExactly("java:java");
        }

        @Test
        @DisplayName("should scan a github marketplace that Claude Code has already cloned locally")
        void discoverSkills_resolvesGithubSourceThroughClaudeCodeRegistry() throws Exception {
            var clonedDir = tempDir.resolve("claude-clone");
            Files.createDirectories(clonedDir.resolve("java/skills/java"));
            Files.createFile(clonedDir.resolve("java/skills/java/SKILL.md"));
            Files.writeString(registryFile,
                "{\"remote-mkt\":{\"installLocation\":\"" + clonedDir.toAbsolutePath() + "\"}}");

            var settings = new Settings()
                .withExtraKnownMarketplaces(List.of(new MarketplaceSource("remote-mkt", null, "github", "owner/repo")));

            assertThat(service.discoverSkills(settings, new Settings()))
                .as("a github marketplace Claude Code has cloned is local — its skills must be visible")
                .extracting(SkillsService.SkillEntry::skillId)
                .containsExactly("java:java");
        }

        @Test
        @DisplayName("should scan marketplaces declared only in the global settings")
        void discoverSkills_includesGloballyDeclaredMarketplaces() throws Exception {
            var globalMarketplace = tempDir.resolve("global-marketplace");
            Files.createDirectories(globalMarketplace.resolve("git/skills/rebase"));
            Files.createFile(globalMarketplace.resolve("git/skills/rebase/SKILL.md"));

            var global = new Settings()
                .withExtraKnownMarketplaces(List.of(
                    new MarketplaceSource("global-mkt", globalMarketplace.toString(), null, null)));

            assertThat(service.discoverSkills(new Settings(), global))
                .as("skills list must see the same marketplaces plugins list does, both scopes included")
                .extracting(SkillsService.SkillEntry::skillId)
                .containsExactly("git:rebase");
        }

        @Test
        @DisplayName("should return all skills across multiple plugins and multiple skills per plugin")
        void discoverSkills_multiplePluginsMultipleSkills() throws Exception {
            var marketplaceDir = tempDir.resolve("marketplace");

            Files.createDirectories(marketplaceDir.resolve("java/skills/java"));
            Files.createFile(marketplaceDir.resolve("java/skills/java/SKILL.md"));
            Files.createDirectories(marketplaceDir.resolve("java/skills/java-testing"));
            Files.createFile(marketplaceDir.resolve("java/skills/java-testing/SKILL.md"));

            Files.createDirectories(marketplaceDir.resolve("quarkus/skills/java-quarkus"));
            Files.createFile(marketplaceDir.resolve("quarkus/skills/java-quarkus/SKILL.md"));
            Files.createDirectories(marketplaceDir.resolve("quarkus/skills/java-persistence"));
            Files.createFile(marketplaceDir.resolve("quarkus/skills/java-persistence/SKILL.md"));

            var settings = settingsWithMarketplace(marketplaceDir);

            var results = service.discoverSkills(settings, new Settings());

            assertThat(results)
                .as("2 plugins with 2 skills each must yield 4 entries")
                .hasSize(4)
                .extracting(SkillsService.SkillEntry::skillId)
                .containsExactlyInAnyOrder(
                    "java:java",
                    "java:java-testing",
                    "quarkus:java-quarkus",
                    "quarkus:java-persistence"
                );
        }
    }

    // -----------------------------------------------------------------------
    // setOverride()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("setOverride")
    class SetOverride {

        @Test
        @DisplayName("should write user-invocable-only into settings.json skillOverrides")
        void setOverride_writesUserInvocableOnly() {
            service.setOverride("java:java", "user-invocable-only", tempDir);

            var settings = settingsService.read(tempDir);

            assertThat(settings.skillOverrides())
                .as("skillOverrides must contain the written entry")
                .isNotNull()
                .containsEntry("java:java", "user-invocable-only");
        }

        @Test
        @DisplayName("should write off into settings.json skillOverrides")
        void setOverride_writesOff() {
            service.setOverride("java:java", "off", tempDir);

            var settings = settingsService.read(tempDir);

            assertThat(settings.skillOverrides())
                .as("skillOverrides must contain the off entry")
                .isNotNull()
                .containsEntry("java:java", "off");
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for any mode other than user-invocable-only or off")
        void setOverride_throwsForInvalidMode() {
            assertThatThrownBy(() -> service.setOverride("java:java", "invalid", tempDir))
                .as("invalid mode must cause IllegalArgumentException before any file I/O")
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should overwrite existing override for the same skill ID")
        void setOverride_overwritesExistingOverride() {
            service.setOverride("java:java", "user-invocable-only", tempDir);
            service.setOverride("java:java", "off", tempDir);

            var settings = settingsService.read(tempDir);

            assertThat(settings.skillOverrides())
                .as("second setOverride must replace the first for the same skillId")
                .containsEntry("java:java", "off")
                .doesNotContainEntry("java:java", "user-invocable-only");
        }
    }

    // -----------------------------------------------------------------------
    // removeOverride()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("removeOverride")
    class RemoveOverride {

        @Test
        @DisplayName("should remove the specified skill ID from skillOverrides")
        void removeOverride_removesEntry() {
            service.setOverride("java:java", "off", tempDir);
            service.setOverride("java:java-testing", "user-invocable-only", tempDir);

            service.removeOverride("java:java", tempDir);

            var settings = settingsService.read(tempDir);

            assertThat(settings.skillOverrides())
                .as("removed skill must be absent; surviving skill must remain")
                .doesNotContainKey("java:java")
                .containsKey("java:java-testing");
        }

        @Test
        @DisplayName("should set skillOverrides to null when the last override is removed")
        void removeOverride_setsNullWhenEmpty() {
            service.setOverride("java:java", "off", tempDir);

            service.removeOverride("java:java", tempDir);

            var settings = settingsService.read(tempDir);

            assertThat(settings.skillOverrides())
                .as("skillOverrides must be null after removing the last entry")
                .isNullOrEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // resetAllOverrides()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("resetAllOverrides")
    class ResetAllOverrides {

        @Test
        @DisplayName("should remove all overrides and return the list of removed skill IDs")
        void resetAllOverrides_removesAllAndReturnsList() {
            service.setOverride("java:java", "off", tempDir);
            service.setOverride("java:java-testing", "user-invocable-only", tempDir);

            var removed = service.resetAllOverrides(tempDir);

            assertThat(removed)
                .as("resetAllOverrides must return all skill IDs that were removed")
                .hasSize(2)
                .containsExactlyInAnyOrder("java:java", "java:java-testing");

            var settings = settingsService.read(tempDir);
            assertThat(settings.skillOverrides())
                .as("skillOverrides must be null after resetAllOverrides")
                .isNullOrEmpty();
        }

        @Test
        @DisplayName("should return empty list when no overrides exist")
        void resetAllOverrides_returnsEmptyListWhenNothingToRemove() {
            var removed = service.resetAllOverrides(tempDir);

            assertThat(removed)
                .as("no overrides present must yield empty return list")
                .isNotNull()
                .isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // getStatus()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("getStatus")
    class GetStatus {

        @TempDir
        Path globalRoot;

        @Test
        @DisplayName("should return auto when no override is present in project or global settings")
        void getStatus_returnsAutoWhenNoOverride() {
            var status = service.getStatus("java:java", tempDir, globalRoot);

            assertThat(status)
                .as("status must be auto when neither project nor global settings have an override")
                .isEqualTo("auto");
        }

        @Test
        @DisplayName("should return project override when both project and global have different overrides")
        void getStatus_projectOverrideTakesPrecedence() {
            service.setOverride("java:java", "off", tempDir);

            var globalSettings = new Settings()
                .withSkillOverrides(new HashMap<>(Map.of("java:java", "user-invocable-only")));
            settingsService.write(globalSettings, globalRoot);

            var status = service.getStatus("java:java", tempDir, globalRoot);

            assertThat(status)
                .as("project override must win over global override")
                .isEqualTo("off");
        }

        @Test
        @DisplayName("should fall back to global override when no project override is set")
        void getStatus_fallsBackToGlobal() {
            var globalSettings = new Settings()
                .withSkillOverrides(new HashMap<>(Map.of("java:java", "user-invocable-only")));
            settingsService.write(globalSettings, globalRoot);

            var status = service.getStatus("java:java", tempDir, globalRoot);

            assertThat(status)
                .as("global override must be used when project has no override for this skill")
                .isEqualTo("user-invocable-only");
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private Settings settingsWithMarketplace(Path marketplaceDir) {
        var source = new MarketplaceSource("test-marketplace", marketplaceDir.toString(), null, null);

        var settings = new Settings()
            .withExtraKnownMarketplaces(List.of(source));
        return settings;
    }
}
