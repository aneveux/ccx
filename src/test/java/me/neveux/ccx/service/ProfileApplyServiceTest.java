package me.neveux.ccx.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import me.neveux.ccx.model.CcxContext;
import me.neveux.ccx.model.Permissions;
import me.neveux.ccx.model.Profile;
import me.neveux.ccx.model.ProfilePlugins;
import me.neveux.ccx.model.Settings;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProfileApplyService")
class ProfileApplyServiceTest {

    private ProfileApplyService service;
    private ProfileService profileService;
    private SettingsService settingsService;

    @TempDir
    Path tempDir;

    @TempDir
    Path ccxConfigDir;

    @BeforeEach
    void setUp() throws Exception {
        var javaProfileDir = ccxConfigDir.resolve("profiles/java");
        Files.createDirectories(javaProfileDir);
        Files.writeString(javaProfileDir.resolve("profile.yaml"),
            "metadata:\n  name: java\n  description: Java/Maven projects\nplugins:\n  enable:\n    - java@claude-java-stack\n");
        var yamlMapper = new ObjectMapper(new YAMLFactory());
        yamlMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        yamlMapper.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
        Files.createDirectories(javaProfileDir.resolve("rules"));
        Files.writeString(javaProfileDir.resolve("rules/java.md"), "# Java rules\nDo Java things.\n");
        profileService = new ProfileService(yamlMapper, new CcxConfigService(ccxConfigDir));
        service = new ProfileApplyService(new SettingsService(), new ClaudeMdService(),
            new PluginMapService(), new CcxConfigService(ccxConfigDir));
        settingsService = new SettingsService();
    }

    private me.neveux.ccx.model.Profile resolvedJavaProfile() {
        return profileService.loadAndResolve("java");
    }

    // -----------------------------------------------------------------------
    // apply()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("apply()")
    class Apply {

        @Test
        @DisplayName("should write plugins to .claude/settings.json")
        void apply_writesPluginsToSettings() throws Exception {
            var resolved = resolvedJavaProfile();

            service.apply(resolved, tempDir);

            var settingsFile = tempDir.resolve(".claude/settings.json");
            assertThat(settingsFile)
                .as(".claude/settings.json must be created by apply")
                .exists();
            var content = Files.readString(settingsFile);
            assertThat(content)
                .as("settings.json must contain the java@ plugin from the java profile")
                .contains("java@claude-java-stack");
        }

        @Test
        @DisplayName("should install rules files to .claude/rules/")
        void apply_installsRulesFiles() throws Exception {
            var resolved = resolvedJavaProfile();

            service.apply(resolved, tempDir);

            var ruleFile = tempDir.resolve(".claude/rules/java.md");
            assertThat(ruleFile)
                .as(".claude/rules/java.md must be created by apply for the java profile")
                .exists();
            assertThat(Files.readString(ruleFile))
                .as("installed rule file must have non-empty content")
                .isNotBlank();
        }

        @Test
        @DisplayName("should install every .md in the profile rules/ dir without any YAML declaration")
        void apply_installsAllRulesFromDir_withoutYamlDeclaration() throws Exception {
            // The profile.yaml written in setUp declares NO rules block — the rule files
            // must still be applied purely from the rules/ directory contents.
            var profileRulesDir = ccxConfigDir.resolve("profiles/java/rules");
            Files.writeString(profileRulesDir.resolve("java-safety.md"), "# Java safety\nNo empty catch.\n");

            var resolved = resolvedJavaProfile();
            var snapshot = service.apply(resolved, tempDir);

            assertThat(tempDir.resolve(".claude/rules/java.md"))
                .as("java.md must be auto-discovered and installed")
                .exists();
            assertThat(tempDir.resolve(".claude/rules/java-safety.md"))
                .as("java-safety.md must be auto-discovered and installed with no YAML entry")
                .exists();
            assertThat(Files.readString(tempDir.resolve(".claude/rules/java-safety.md")))
                .as("installed rule content must match the source")
                .contains("No empty catch");
            assertThat(snapshot.rules())
                .as("snapshot must record both auto-discovered rule basenames")
                .containsExactlyInAnyOrder("java", "java-safety");
        }

        @Test
        @DisplayName("should not overwrite an existing rule file when overwrite is false")
        void apply_skipsExistingRuleFile_whenOverwriteFalse() throws Exception {
            var rulesDir = tempDir.resolve(".claude/rules");
            Files.createDirectories(rulesDir);
            var ruleFile = rulesDir.resolve("java.md");
            Files.writeString(ruleFile, "custom content");

            var resolved = resolvedJavaProfile();
            service.apply(resolved, tempDir, false);

            assertThat(Files.readString(ruleFile))
                .as("apply with overwrite=false must not overwrite an existing rule file")
                .isEqualTo("custom content");
        }

        @Test
        @EnabledOnOs(OS.LINUX)
        @DisplayName("should not install a rule file whose real path lies outside configDir (symlink guard)")
        void apply_skipsRuleFileFromSymlinkOutsideConfigDir() throws Exception {
            var victim = tempDir.resolve("victim.txt");
            Files.writeString(victim, "LEAKED CONTENT");

            var profileRulesDir = ccxConfigDir.resolve("profiles/java/rules");
            var symlinkPath = profileRulesDir.resolve("evil.md");
            Files.createSymbolicLink(symlinkPath, victim.toAbsolutePath());

            var resolved = resolvedJavaProfile();
            service.apply(resolved, tempDir, true);

            assertThat(tempDir.resolve(".claude/rules/evil.md"))
                .as("rule file resolving outside configDir via symlink must NOT be installed")
                .doesNotExist();
        }

        @Test
        @DisplayName("should merge permissions.allow into settings when profile has permissions")
        void apply_mergesPermissionsToSettings_whenProfileHasPermissions() {
            var permissions = new Permissions(List.of("Bash(*)"), null);
            var profile = new Profile()
                .withPermissions(permissions);

            service.apply(profile, tempDir);

            var settings = settingsService.read(tempDir);
            assertThat(settings.permissions())
                .as("settings.permissions must not be null after applying a profile with permissions")
                .isNotNull();
            assertThat(settings.permissions().allow())
                .as("settings.permissions.allow must contain the permission from the profile")
                .contains("Bash(*)");
        }

        @Test
        @DisplayName("should return a snapshot with plugins and rules populated")
        void apply_returnsPopulatedSnapshot() {
            var resolved = resolvedJavaProfile();

            var snapshot = service.apply(resolved, tempDir);

            assertThat(snapshot)
                .as("apply must return a non-null snapshot")
                .isNotNull();
            assertThat(snapshot.plugins())
                .as("snapshot plugins must be non-empty and contain the java plugin")
                .isNotEmpty()
                .contains("java@claude-java-stack");
            assertThat(snapshot.rules())
                .as("snapshot rules must be non-empty and contain the java rule")
                .isNotEmpty()
                .contains("java");
        }

        @Test
        @DisplayName("should deploy command files from profile commands/ dir to .claude/commands/")
        void apply_deploysCommandFilesFromProfileDir() throws Exception {
            var commandsDir = ccxConfigDir.resolve("profiles/java/commands");
            Files.createDirectories(commandsDir);
            Files.writeString(commandsDir.resolve("review.md"), "# Review command\n");

            var resolved = resolvedJavaProfile();
            service.apply(resolved, tempDir);

            var deployedCommand = tempDir.resolve(".claude/commands/review.md");
            assertThat(deployedCommand)
                .as(".claude/commands/review.md must be created from the profile commands/ dir")
                .exists();
            assertThat(Files.readString(deployedCommand))
                .as("deployed command file must have the expected content")
                .contains("Review command");
        }

        @Test
        @DisplayName("should write env vars from profile to settings.json")
        void apply_writesEnvVarsToSettings() throws Exception {
            var env = new java.util.LinkedHashMap<String, String>();
            env.put("ENABLE_LSP_TOOL", "1");
            env.put("DEBUG", "true");
            var profile = new Profile()
                .withEnv(env);

            service.apply(profile, tempDir);

            var settings = settingsService.read(tempDir);
            assertThat(settings.env())
                .as("settings.env must contain the env vars from the profile")
                .isNotNull()
                .containsEntry("ENABLE_LSP_TOOL", "1")
                .containsEntry("DEBUG", "true");
        }

        @Test
        @DisplayName("should append ignore patterns to settings.json permissions.deny")
        void apply_appendsIgnorePatterns() {
            var profile = new Profile()
                .withIgnore(List.of("target/", ".mvn/"));

            service.apply(profile, tempDir);

            assertThat(tempDir.resolve(".claudeignore"))
                .as(".claudeignore is not a real Claude Code feature — it must never be created")
                .doesNotExist();
            var settings = settingsService.read(tempDir);
            assertThat(settings.permissions())
                .as("settings.permissions must not be null when the profile has ignore patterns")
                .isNotNull();
            assertThat(settings.permissions().deny())
                .as("target/ must translate to the Read(target/**) deny rule")
                .contains("Read(target/**)")
                .as(".mvn/ must translate to the Read(.mvn/**) deny rule")
                .contains("Read(.mvn/**)");
        }

        @Test
        @DisplayName("should add fragment @path entries to .claude/CLAUDE.md marker block")
        void apply_addsFragmentPathsToClaudeMd() throws Exception {
            var fragmentsDir = ccxConfigDir.resolve("profiles/java/claudemd");
            Files.createDirectories(fragmentsDir);
            Files.writeString(fragmentsDir.resolve("java-intro.md"), "# Java intro\n");

            var resolved = resolvedJavaProfile();
            service.apply(resolved, tempDir);

            var claudeMd = tempDir.resolve(".claude/CLAUDE.md");
            assertThat(claudeMd).as(".claude/CLAUDE.md must be created").exists();
            var content = Files.readString(claudeMd);
            var expectedFragmentPath = fragmentsDir.resolve("java-intro.md").toAbsolutePath().normalize().toString();
            assertThat(content)
                .as(".claude/CLAUDE.md must contain @{absoluteFragmentPath} in the ccx block")
                .contains("@" + expectedFragmentPath);
        }

        @Test
        @DisplayName("should not overwrite an existing command file when overwrite is false")
        void apply_skipsExistingCommandFile_whenOverwriteFalse() throws Exception {
            var commandsDir = ccxConfigDir.resolve("profiles/java/commands");
            Files.createDirectories(commandsDir);
            Files.writeString(commandsDir.resolve("review.md"), "# Profile version\n");

            var targetCommandsDir = tempDir.resolve(".claude/commands");
            Files.createDirectories(targetCommandsDir);
            Files.writeString(targetCommandsDir.resolve("review.md"), "# My custom version\n");

            var resolved = resolvedJavaProfile();
            service.apply(resolved, tempDir, false);

            assertThat(Files.readString(targetCommandsDir.resolve("review.md")))
                .as("apply with overwrite=false must not overwrite an existing command file")
                .isEqualTo("# My custom version\n");
        }

        @Test
        @EnabledOnOs(OS.LINUX)
        @DisplayName("should not deploy a command file whose real path lies outside configDir (symlink guard)")
        void apply_skipsCommandFileFromSymlinkOutsideConfigDir() throws Exception {
            // Create a victim file OUTSIDE ccxConfigDir
            var victim = tempDir.resolve("victim.txt");
            Files.writeString(victim, "LEAKED CONTENT");

            // Create the commands directory inside the profile
            var commandsDir = ccxConfigDir.resolve("profiles/java/commands");
            Files.createDirectories(commandsDir);

            // Create a symlink pointing outside ccxConfigDir
            var symlinkPath = commandsDir.resolve("evil.md");
            Files.createSymbolicLink(symlinkPath, victim.toAbsolutePath());

            var resolved = resolvedJavaProfile();
            service.apply(resolved, tempDir, true);

            // The guard must prevent deploying the file that points outside configDir
            var deployed = tempDir.resolve(".claude/commands/evil.md");
            assertThat(deployed)
                .as("command file resolving outside configDir via symlink must NOT be deployed")
                .doesNotExist();
        }

        @Test
        @EnabledOnOs(OS.LINUX)
        @DisplayName("should not write a fragment @path to .claude/CLAUDE.md when fragment resolves outside configDir (symlink guard)")
        void apply_skipsFragmentFromSymlinkOutsideConfigDir() throws Exception {
            // Create a secret file OUTSIDE ccxConfigDir
            var secret = tempDir.resolve("secret.txt");
            Files.writeString(secret, "SECRET CONTENT");

            // Create the claudemd directory inside the profile
            var fragmentsDir = ccxConfigDir.resolve("profiles/java/claudemd");
            Files.createDirectories(fragmentsDir);

            // Create a symlink pointing outside ccxConfigDir
            var symlinkPath = fragmentsDir.resolve("evil-frag.md");
            Files.createSymbolicLink(symlinkPath, secret.toAbsolutePath());

            var resolved = resolvedJavaProfile();
            service.apply(resolved, tempDir, true);

            // The guard must prevent writing ANY @-reference for a fragment whose real path is outside configDir.
            // The symlink path itself (evil-frag.md) is within configDir by string, but its real path escapes it.
            // A proper guard calls toRealPath() before the startsWith(configDir) check.
            // Without the guard: evil-frag.md IS listed in CLAUDE.md (as @<symlink-path>).
            // With the guard: evil-frag.md is skipped entirely — no @-line for it.
            var claudeMd = tempDir.resolve(".claude/CLAUDE.md");
            var symlinkAbsPath = symlinkPath.toAbsolutePath().normalize().toString();
            assertThat(Files.readString(claudeMd))
                .as(".claude/CLAUDE.md must not reference the symlink path whose real path escapes configDir")
                .doesNotContain(symlinkAbsPath);
        }

        @Test
        @DisplayName("should write plugins, env, and permissions all present in settings.json after a single apply")
        void apply_writesPluginsAndEnvAndPermissionsInSingleCycle() throws Exception {
            // Env
            var env = new java.util.LinkedHashMap<String, String>();
            env.put("MY_VAR", "hello");

            // Permissions
            var permissions = new Permissions(List.of("Bash(mvn *)"), null);

            var profile = new Profile()
                .withPlugins(new ProfilePlugins(List.of("my-plugin@test-market")))
                .withEnv(env)
                .withPermissions(permissions);

            service.apply(profile, tempDir);

            // Read back settings and assert all three are present simultaneously
            var settings = settingsService.read(tempDir);

            assertThat(settings.enabledPlugins())
                .as("settings.enabledPlugins must contain the plugin after apply")
                .isNotNull()
                .containsKey("my-plugin@test-market");

            assertThat(settings.env())
                .as("settings.env must contain MY_VAR=hello after apply")
                .isNotNull()
                .containsEntry("MY_VAR", "hello");

            assertThat(settings.permissions())
                .as("settings.permissions must not be null after apply")
                .isNotNull();
            assertThat(settings.permissions().allow())
                .as("settings.permissions.allow must contain Bash(mvn *) after apply")
                .contains("Bash(mvn *)");
        }
    }

    // -----------------------------------------------------------------------
    // remove()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("remove()")
    class Remove {

        private CcxContext.ProfileSnapshot applyJavaAndManualSetup() throws Exception {
            var resolved = resolvedJavaProfile();
            var snapshot = service.apply(resolved, tempDir);

            // Add a manual plugin that should survive remove
            var settingsFile = tempDir.resolve(".claude/settings.json");
            var content = Files.readString(settingsFile);
            // Inject a manual plugin key into the JSON — replace closing brace to add entry
            var withManual = content.replace("\"enabledPlugins\" : {",
                "\"enabledPlugins\" : { \"manual-tool@somewhere\": true,");
            Files.writeString(settingsFile, withManual);

            return snapshot;
        }

        @Test
        @DisplayName("should remove only snapshot plugins, leaving manually-added plugins intact")
        void remove_removesOnlySnapshotPlugins() throws Exception {
            var snapshot = applyJavaAndManualSetup();

            service.remove(snapshot, tempDir);

            var settingsFile = tempDir.resolve(".claude/settings.json");
            var content = Files.readString(settingsFile);
            assertThat(content)
                .as("remove must not delete the manually-added plugin")
                .contains("manual-tool@somewhere");
            assertThat(content)
                .as("remove must delete the snapshot plugin java@claude-java-stack")
                .doesNotContain("java@claude-java-stack");
        }

        @Test
        @DisplayName("should remove only snapshot rules files, leaving other rules intact")
        void remove_removesOnlySnapshotRulesFiles() throws Exception {
            var resolved = resolvedJavaProfile();
            var snapshot = service.apply(resolved, tempDir);

            // Create a manual rule file that should survive
            var rulesDir = tempDir.resolve(".claude/rules");
            Files.createDirectories(rulesDir);
            Files.writeString(rulesDir.resolve("manual.md"), "# Manual rule\n");

            service.remove(snapshot, tempDir);

            assertThat(rulesDir.resolve("manual.md"))
                .as("remove must not delete rule files not in the snapshot")
                .exists();
            assertThat(rulesDir.resolve("java.md"))
                .as("remove must delete the snapshot rule file java.md")
                .doesNotExist();
        }

        @Test
        @DisplayName("should not remove permissions when disabling a profile — permissions are additive")
        void remove_doesNotRemovePermissions() {
            var permissions = new Permissions(List.of("Bash(*)"), null);
            var profile = new Profile()
                .withPermissions(permissions);

            var snapshot = service.apply(profile, tempDir);
            service.remove(snapshot, tempDir);

            var settings = settingsService.read(tempDir);
            assertThat(settings.permissions())
                .as("permissions must not be null after remove — permissions are never removed")
                .isNotNull();
            assertThat(settings.permissions().allow())
                .as("permissions.allow must still contain Bash(*) — remove does not revoke permissions")
                .contains("Bash(*)");
        }

        @Test
        @DisplayName("should remove only snapshot env vars, leaving other env vars intact")
        void remove_removesOnlySnapshotEnvVars() throws Exception {
            var snapshot = new CcxContext.ProfileSnapshot(
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                java.util.Map.of("ENABLE_LSP_TOOL", "1"),
                List.of()
            );

            var settings = settingsService.read(tempDir);
            var env = new java.util.LinkedHashMap<String, String>();
            env.put("ENABLE_LSP_TOOL", "1");
            env.put("MANUAL_VAR", "keep");
            settings = settings.withEnv(env);
            settingsService.write(settings, tempDir);

            service.remove(snapshot, tempDir);

            var updated = settingsService.read(tempDir);
            assertThat(updated.env())
                .as("MANUAL_VAR must survive remove")
                .containsEntry("MANUAL_VAR", "keep");
            assertThat(updated.env())
                .as("ENABLE_LSP_TOOL must be removed")
                .doesNotContainKey("ENABLE_LSP_TOOL");
        }

        @Test
        @DisplayName("should remove only snapshot ignore deny rules, leaving other deny rules intact")
        void remove_removesOnlySnapshotIgnorePatterns() {
            var permissions = new Permissions(null,
                List.of("Read(target/**)", "Read(.mvn/**)", "Read(manual-pattern/**)"));
            settingsService.write(new Settings().withPermissions(permissions), tempDir);

            var snapshot = new CcxContext.ProfileSnapshot(
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null,
                List.of("target/", ".mvn/")
            );

            service.remove(snapshot, tempDir);

            var settings = settingsService.read(tempDir);
            assertThat(settings.permissions())
                .as("permissions must survive remove — unrelated deny rules are still present")
                .isNotNull();
            assertThat(settings.permissions().deny())
                .as("Read(manual-pattern/**) was not derived from a snapshot pattern — it must survive")
                .contains("Read(manual-pattern/**)")
                .as("Read(target/**) and Read(.mvn/**) came from snapshot patterns — both must be removed")
                .doesNotContain("Read(target/**)", "Read(.mvn/**)");
        }

        @Test
        @DisplayName("should still match a legacy snapshot pattern carrying stray whitespace")
        void remove_stripsWhitespaceFromLegacySnapshotPatterns() {
            var permissions = new Permissions(null, List.of("Read(target/**)"));
            settingsService.write(new Settings().withPermissions(permissions), tempDir);

            var snapshot = new CcxContext.ProfileSnapshot(
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null,
                List.of("  target/  ")
            );

            service.remove(snapshot, tempDir);

            assertThat(settingsService.read(tempDir).permissions())
                .as("a pattern recorded before stripping existed must still translate to "
                    + "Read(target/**) — removing the last deny rule clears the permissions block")
                .isNull();
        }

        @Test
        @DisplayName("should remove command files tracked in snapshot")
        void remove_removesSnapshotCommandFiles() throws Exception {
            var commandsDir = tempDir.resolve(".claude/commands");
            Files.createDirectories(commandsDir);
            Files.writeString(commandsDir.resolve("review.md"), "# Review command\n");
            Files.writeString(commandsDir.resolve("manual.md"), "# Manual command\n");

            var snapshot = new CcxContext.ProfileSnapshot(
                List.of(), List.of(), List.of(), List.of(),
                List.of("review"), List.of(), null, List.of()
            );

            service.remove(snapshot, tempDir);

            assertThat(commandsDir.resolve("review.md")).as("review.md must be removed").doesNotExist();
            assertThat(commandsDir.resolve("manual.md")).as("manual.md must survive").exists();
        }

        // -----------------------------------------------------------------------
        // safe remove overload respects sibling profiles
        // -----------------------------------------------------------------------

        @Nested
        @DisplayName("remove(snapshot, projectRoot, context, profileName) — safe overload")
        class WhenRemovingSafeWithSiblingProfile {

            /**
             * Build a CcxContext where both profileA and profileB are enabled,
             * each with their own snapshot, so the safe remove overload can
             * detect that profileA still claims java@mkt.
             */
            private CcxContext contextWithBothEnabled(CcxContext.ProfileSnapshot snapshotA,
                                                       CcxContext.ProfileSnapshot snapshotB) {
                var now = java.time.Instant.now();
                return new me.neveux.ccx.model.CcxContext("1", java.util.Map.of(
                    "A", new me.neveux.ccx.model.CcxContext.ProfileRecord(now, snapshotA),
                    "B", new me.neveux.ccx.model.CcxContext.ProfileRecord(now, snapshotB)
                ));
            }

            @Test
            @DisplayName("plugin claimed by a sibling active profile must survive when disabling one profile")
            void safeRemove_pluginClaimedBySibling_isPreserved() throws Exception {
                // Profile A: has java@mkt
                var snapshotA = new CcxContext.ProfileSnapshot(
                    List.of("java@mkt"), List.of(), List.of(), List.of(),
                    List.of(), List.of(), null, List.of()
                );
                // Profile B (extends A): has both java@mkt (inherited) and cli@mkt (own)
                var snapshotB = new CcxContext.ProfileSnapshot(
                    List.of("java@mkt", "cli@mkt"), List.of(), List.of(), List.of(),
                    List.of(), List.of(), null, List.of()
                );

                // Write settings with both plugins enabled
                var settings = settingsService.read(tempDir);
                var enabledPlugins = new java.util.LinkedHashMap<String, Boolean>();
                enabledPlugins.put("java@mkt", true);
                enabledPlugins.put("cli@mkt", true);
                settings = settings.withEnabledPlugins(enabledPlugins);
                settingsService.write(settings, tempDir);

                var context = contextWithBothEnabled(snapshotA, snapshotB);

                // Act: disable B using the safe overload
                service.remove(snapshotB, tempDir, context, "B");

                // Assert: java@mkt must survive (still claimed by A's snapshot)
                var updated = settingsService.read(tempDir);
                assertThat(updated.enabledPlugins())
                    .as("java@mkt must remain in settings because profile A's snapshot still claims it")
                    .containsKey("java@mkt");
            }

            @Test
            @DisplayName("plugin exclusive to the removed profile must be deleted")
            void safeRemove_pluginExclusiveToBProfile_isRemoved() throws Exception {
                var snapshotA = new CcxContext.ProfileSnapshot(
                    List.of("java@mkt"), List.of(), List.of(), List.of(),
                    List.of(), List.of(), null, List.of()
                );
                var snapshotB = new CcxContext.ProfileSnapshot(
                    List.of("java@mkt", "cli@mkt"), List.of(), List.of(), List.of(),
                    List.of(), List.of(), null, List.of()
                );

                var settings = settingsService.read(tempDir);
                var enabledPlugins = new java.util.LinkedHashMap<String, Boolean>();
                enabledPlugins.put("java@mkt", true);
                enabledPlugins.put("cli@mkt", true);
                settings = settings.withEnabledPlugins(enabledPlugins);
                settingsService.write(settings, tempDir);

                var context = contextWithBothEnabled(snapshotA, snapshotB);

                service.remove(snapshotB, tempDir, context, "B");

                var updated = settingsService.read(tempDir);
                assertThat(updated.enabledPlugins())
                    .as("cli@mkt must be removed — only profile B claimed it and B is being disabled")
                    .doesNotContainKey("cli@mkt");
            }

            @Test
            @DisplayName("safe overload with no sibling profiles behaves identically to plain remove")
            void safeRemove_noSiblings_removesAllSnapshotPlugins() throws Exception {
                var snapshot = new CcxContext.ProfileSnapshot(
                    List.of("java@mkt"), List.of(), List.of(), List.of(),
                    List.of(), List.of(), null, List.of()
                );

                var settings = settingsService.read(tempDir);
                var enabledPlugins = new java.util.LinkedHashMap<String, Boolean>();
                enabledPlugins.put("java@mkt", true);
                settings = settings.withEnabledPlugins(enabledPlugins);
                settingsService.write(settings, tempDir);

                // Context has only profile A — no sibling
                var now = java.time.Instant.now();
                var context = new me.neveux.ccx.model.CcxContext("1", java.util.Map.of(
                    "A", new me.neveux.ccx.model.CcxContext.ProfileRecord(now, snapshot)
                ));

                service.remove(snapshot, tempDir, context, "A");

                var updated = settingsService.read(tempDir);
                assertThat(updated.enabledPlugins())
                    .as("java@mkt must be removed when no sibling profile claims it")
                    .doesNotContainKey("java@mkt");
            }

            @Test
            @DisplayName("rule file claimed by a sibling must NOT be deleted")
            void safeRemove_ruleClaimedBySibling_isPreserved() throws Exception {
                // Create the rule file on disk
                var rulesDir = tempDir.resolve(".claude/rules");
                Files.createDirectories(rulesDir);
                Files.writeString(rulesDir.resolve("java.md"), "# Java rules");

                var snapshotA = new CcxContext.ProfileSnapshot(
                    List.of(), List.of("java"), List.of(), List.of(),
                    List.of(), List.of(), null, List.of()
                );
                var snapshotB = new CcxContext.ProfileSnapshot(
                    List.of(), List.of("java"), List.of(), List.of(),
                    List.of(), List.of(), null, List.of()
                );

                var context = contextWithBothEnabled(snapshotA, snapshotB);

                service.remove(snapshotB, tempDir, context, "B");

                assertThat(rulesDir.resolve("java.md"))
                    .as("java.md rule must NOT be deleted because profile A's snapshot also claims it")
                    .exists();
            }

            @Test
            @DisplayName("rule file exclusive to removed profile is deleted")
            void safeRemove_ruleExclusiveToRemovedProfile_isDeleted() throws Exception {
                var rulesDir = tempDir.resolve(".claude/rules");
                Files.createDirectories(rulesDir);
                Files.writeString(rulesDir.resolve("cli.md"), "# CLI rules");

                var snapshotA = new CcxContext.ProfileSnapshot(
                    List.of(), List.of("java"), List.of(), List.of(),
                    List.of(), List.of(), null, List.of()
                );
                var snapshotB = new CcxContext.ProfileSnapshot(
                    List.of(), List.of("cli"), List.of(), List.of(),
                    List.of(), List.of(), null, List.of()
                );

                var context = contextWithBothEnabled(snapshotA, snapshotB);

                service.remove(snapshotB, tempDir, context, "B");

                assertThat(rulesDir.resolve("cli.md"))
                    .as("cli.md must be deleted — only profile B claimed it")
                    .doesNotExist();
            }
        }

        // -----------------------------------------------------------------------
        // Plan 046 — remove() undoes deny-rule translations for snapshot ignorePatterns
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("remove() must delete ignore-pattern deny rules from settings, leaving other deny rules")
        void remove_removesIgnorePatternDenyRulesFromSettings() {
            // Pre-populate settings with two deny rules: one that belongs to a profile
            // ignore pattern and one that was added manually.
            var permissions = new Permissions(null, List.of("Read(target/**)", "Read(secret.txt)"));
            settingsService.write(new Settings().withPermissions(permissions), tempDir);

            // Snapshot records only the ignore pattern "target/" — not "Read(secret.txt)".
            var snapshot = new CcxContext.ProfileSnapshot(
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null,
                List.of("target/")
            );

            service.remove(snapshot, tempDir);

            var settings = settingsService.read(tempDir);
            assertThat(settings.permissions())
                .as("permissions must not be null after remove — deny rules still present")
                .isNotNull();
            assertThat(settings.permissions().deny())
                .as("Read(secret.txt) was not in the snapshot — must survive remove")
                .contains("Read(secret.txt)");
            assertThat(settings.permissions().deny())
                .as("Read(target/**) corresponds to snapshot ignore pattern target/ — must be removed")
                .doesNotContain("Read(target/**)");
        }

        @Test
        @DisplayName("should remove fragment @path entries from .claude/CLAUDE.md marker block")
        void remove_removesFragmentEntriesFromClaudeMd() throws Exception {
            var fragmentAbsPath = "/home/user/.config/ccx/profiles/java/claudemd/java-intro.md";
            var claudeDir = tempDir.resolve(".claude");
            Files.createDirectories(claudeDir);
            var claudeMd = claudeDir.resolve("CLAUDE.md");
            Files.writeString(claudeMd,
                "<!-- ccx:begin -->\n@/some/other.md\n@" + fragmentAbsPath + "\n<!-- ccx:end -->\n");

            var snapshot = new CcxContext.ProfileSnapshot(
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(fragmentAbsPath), null, List.of()
            );

            service.remove(snapshot, tempDir);

            var content = Files.readString(claudeMd);
            assertThat(content).as("fragment entry must be removed").doesNotContain(fragmentAbsPath);
            assertThat(content).as("other entry must survive").contains("other.md");
        }
    }

    // -----------------------------------------------------------------------
    // Plan 046 — ignore patterns as deny rules (replaces .claudeignore writes)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Plan 046 — ignore patterns as permissions.deny rules")
    class IgnoreAsDenyRules {

        @Test
        @DisplayName("apply() with ignore patterns must write translated deny rules to settings.json")
        void apply_writesDenyRulesForIgnorePatterns() {
            var profile = new Profile()
                .withIgnore(List.of("target/", "*.class"));

            service.apply(profile, tempDir);

            var settings = settingsService.read(tempDir);
            assertThat(settings.permissions())
                .as("settings.permissions must not be null after applying a profile with ignore patterns")
                .isNotNull();
            assertThat(settings.permissions().deny())
                .as("target/ must translate to Read(target/**) in permissions.deny")
                .contains("Read(target/**)")
                .as("*.class must translate to Read(**/*.class) in permissions.deny")
                .contains("Read(**/*.class)");
        }

        @Test
        @DisplayName("apply() with ignore patterns must not create .claudeignore")
        void apply_doesNotCreateClaudeIgnoreForIgnorePatterns() {
            var profile = new Profile()
                .withIgnore(List.of("target/", "*.class"));

            service.apply(profile, tempDir);

            assertThat(tempDir.resolve(".claudeignore"))
                .as("apply must not create .claudeignore — ignore patterns migrate to permissions.deny")
                .doesNotExist();
        }

        @Test
        @DisplayName("ignore patterns applied via apply() must appear in the snapshot ignorePatterns")
        void apply_recordsIgnorePatternsInSnapshot() {
            var profile = new Profile()
                .withIgnore(List.of("target/", "*.class"));

            var snapshot = service.apply(profile, tempDir);

            assertThat(snapshot.ignorePatterns())
                .as("snapshot must record the source ignore patterns that were translated to deny rules")
                .containsExactlyInAnyOrder("target/", "*.class");
        }

        @Test
        @DisplayName("pattern with trailing slash translates to Read(<pattern>**) deny rule")
        void apply_translatesTrailingSlashPatternToReadDenyRule() {
            var profile = new Profile().withIgnore(List.of("build/"));

            service.apply(profile, tempDir);

            var settings = settingsService.read(tempDir);
            assertThat(settings.permissions())
                .as("permissions must not be null after applying ignore patterns as deny rules")
                .isNotNull();
            assertThat(settings.permissions().deny())
                .as("build/ must translate to Read(build/**)")
                .contains("Read(build/**)");
        }

        @Test
        @DisplayName("glob pattern without path separator translates to Read(**/<pattern>) deny rule")
        void apply_translatesGlobPatternToRecursiveReadDenyRule() {
            var profile = new Profile().withIgnore(List.of("*.log"));

            service.apply(profile, tempDir);

            var settings = settingsService.read(tempDir);
            assertThat(settings.permissions())
                .as("permissions must not be null after applying ignore patterns as deny rules")
                .isNotNull();
            assertThat(settings.permissions().deny())
                .as("*.log (no slash) must translate to Read(**/*.log)")
                .contains("Read(**/*.log)");
        }

        @Test
        @DisplayName("plain filename (no slash, no wildcard) translates to Read(<pattern>) deny rule")
        void apply_translatesPlainFilenameToReadDenyRule() {
            var profile = new Profile().withIgnore(List.of("secrets.txt"));

            service.apply(profile, tempDir);

            var settings = settingsService.read(tempDir);
            assertThat(settings.permissions())
                .as("permissions must not be null after applying ignore patterns as deny rules")
                .isNotNull();
            assertThat(settings.permissions().deny())
                .as("secrets.txt (plain) must translate to Read(secrets.txt)")
                .contains("Read(secrets.txt)");
        }

        @Test
        @DisplayName("pre-formatted Read/Write/Bash patterns pass through unchanged as deny rules")
        void apply_passesThrough_preFormattedDenyPatterns() {
            var profile = new Profile()
                .withIgnore(List.of("Read(custom/**)", "Write(other)", "Bash(secret *)"));

            service.apply(profile, tempDir);

            var settings = settingsService.read(tempDir);
            assertThat(settings.permissions())
                .as("permissions must not be null after applying ignore patterns as deny rules")
                .isNotNull();
            assertThat(settings.permissions().deny())
                .as("pre-formatted Read/Write/Bash patterns must pass through without re-wrapping")
                .contains("Read(custom/**)")
                .contains("Write(other)")
                .contains("Bash(secret *)");
        }

        @Test
        @DisplayName("pattern whose deny rule was already present before apply is not in the snapshot")
        void apply_snapshotExcludesPatternAlreadyPresentAsDenyRule() {
            // Pre-populate settings.permissions.deny with the rule that target/ would translate to.
            var existingPermissions = new Permissions(null, List.of("Read(target/**)"));
            settingsService.write(new Settings().withPermissions(existingPermissions), tempDir);

            var profile = new Profile().withIgnore(List.of("target/", "*.class"));

            var snapshot = service.apply(profile, tempDir);

            assertThat(snapshot.ignorePatterns())
                .as("target/ maps to Read(target/**) which was pre-existing — must NOT be in snapshot")
                .doesNotContain("target/");
            assertThat(snapshot.ignorePatterns())
                .as("*.class maps to Read(**/*.class) which was absent — must be recorded in snapshot")
                .contains("*.class");
        }

        @Test
        @DisplayName("glob pattern containing a path separator is wrapped verbatim, not re-rooted")
        void apply_translatesGlobWithSeparator_verbatim() {
            var profile = new Profile().withIgnore(List.of("docs/*.md"));

            service.apply(profile, tempDir);

            var settings = settingsService.read(tempDir);
            assertThat(settings.permissions())
                .as("permissions must not be null after applying ignore patterns as deny rules")
                .isNotNull();
            assertThat(settings.permissions().deny())
                .as("docs/*.md already has a separator — must NOT become Read(**/docs/*.md)")
                .contains("Read(docs/*.md)")
                .doesNotContain("Read(**/docs/*.md)");
        }

        @Test
        @DisplayName("blank and null ignore entries are skipped, never translated")
        void apply_skipsBlankAndNullIgnorePatterns() {
            var ignore = new java.util.ArrayList<String>();
            ignore.add("target/");
            ignore.add("");
            ignore.add("   ");
            ignore.add(null);

            var snapshot = service.apply(new Profile().withIgnore(ignore), tempDir);

            var settings = settingsService.read(tempDir);
            assertThat(settings.permissions())
                .as("permissions must not be null after applying the one real pattern")
                .isNotNull();
            assertThat(settings.permissions().deny())
                .as("only the one real pattern may produce a deny rule")
                .containsExactly("Read(target/**)");
            assertThat(snapshot.ignorePatterns())
                .as("blank and null entries must not be recorded for removal tracking")
                .containsExactly("target/");
        }

        @Test
        @DisplayName("surrounding whitespace is stripped before translation")
        void apply_stripsWhitespaceAroundIgnorePatterns() {
            var profile = new Profile().withIgnore(List.of("  target/  ", " *.class "));

            var snapshot = service.apply(profile, tempDir);

            var settings = settingsService.read(tempDir);
            assertThat(settings.permissions())
                .as("permissions must not be null after applying ignore patterns as deny rules")
                .isNotNull();
            assertThat(settings.permissions().deny())
                .as("a padded pattern must not yield an inert rule like Read(  target/  **)")
                .containsExactly("Read(target/**)", "Read(**/*.class)");
            assertThat(snapshot.ignorePatterns())
                .as("the stripped pattern must be recorded so remove() translates it identically")
                .containsExactly("target/", "*.class");
        }
    }

    // -----------------------------------------------------------------------
    // Deny-rule provenance: telling a rule ccx wrote apart from one the user wrote
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ignore deny-rule provenance across enables")
    class IgnoreDenyRuleProvenance {

        private CcxContext contextWith(String profileName, CcxContext.ProfileSnapshot snapshot) {
            return new CcxContext("1", Map.of(
                profileName, new CcxContext.ProfileRecord(Instant.now(), snapshot)));
        }

        @Test
        @DisplayName("re-enabling the same profile keeps its deny rules attributed to it")
        void apply_reRecordsOwnDenyRulesOnSecondEnable() {
            var profile = new Profile().withIgnore(List.of("target/"));
            var first = service.apply(profile, tempDir);
            assertThat(first.ignorePatterns())
                .as("first enable writes the rule, so it must be recorded")
                .containsExactly("target/");

            var second = service.apply(profile, tempDir, false, contextWith("java", first));

            assertThat(second.ignorePatterns())
                .as("Read(target/**) is present only because this profile put it there — "
                    + "re-enabling must keep it attributed, or disable can never remove it")
                .containsExactly("target/");
        }

        @Test
        @DisplayName("a second profile records a deny rule a sibling profile already contributed")
        void apply_recordsDenyRuleContributedBySiblingProfile() {
            var profileA = new Profile().withIgnore(List.of("target/"));
            var snapshotA = service.apply(profileA, tempDir);

            var profileB = new Profile().withIgnore(List.of("target/", "*.class"));
            var snapshotB = service.apply(profileB, tempDir, false, contextWith("A", snapshotA));

            assertThat(snapshotB.ignorePatterns())
                .as("both profiles declare target/, so both must claim it — otherwise disabling A "
                    + "strips a rule B still needs")
                .containsExactly("target/", "*.class");
        }

        @Test
        @DisplayName("a hand-written deny rule no profile recorded is never claimed")
        void apply_doesNotRecordUserOwnedDenyRule() {
            settingsService.write(
                new Settings().withPermissions(new Permissions(null, List.of("Read(secret/**)"))),
                tempDir);

            var profile = new Profile().withIgnore(List.of("secret/", "target/"));

            var snapshot = service.apply(profile, tempDir, false, new CcxContext("1", Map.of()));

            assertThat(snapshot.ignorePatterns())
                .as("no recorded profile contributed Read(secret/**), so it is the user's rule "
                    + "and must survive a later disable")
                .containsExactly("target/");
        }
    }
}
