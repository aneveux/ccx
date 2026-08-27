package me.neveux.ccx.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ProfileLifecycleService")
class ProfileLifecycleServiceTest {

    private ProfileLifecycleService service;
    private CcxContextService ccxContextService;
    private SettingsService settingsService;

    @TempDir
    Path projectDir;

    @TempDir
    Path ccxConfigDir;

    @BeforeEach
    void setUp() throws Exception {
        writeProfile("java", "metadata:\n  name: java\nplugins:\n  enable:\n    - java@mkt\n");
        Files.createDirectories(ccxConfigDir.resolve("profiles/java/rules"));
        Files.writeString(ccxConfigDir.resolve("profiles/java/rules/java.md"), "# Java rules\n");
        writeProfile("java-cli",
            "metadata:\n  name: java-cli\nextends:\n  - java\nplugins:\n  enable:\n    - java-cli@mkt\n");
        writeProfile("orphan", "metadata:\n  name: orphan\nextends:\n  - nowhere\n");

        var yamlMapper = new ObjectMapper(new YAMLFactory());
        yamlMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        yamlMapper.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
        var ccxConfigService = new CcxConfigService(ccxConfigDir);
        settingsService = new SettingsService();
        ccxContextService = new CcxContextService();
        service = new ProfileLifecycleService(
            new ProfileService(yamlMapper, ccxConfigService),
            new ProfileApplyService(settingsService, new ClaudeMdService(),
                new PluginMapService(), ccxConfigService),
            ccxContextService);
        Files.createDirectories(projectDir.resolve(".claude"));
    }

    private void writeProfile(String name, String yaml) throws IOException {
        var dir = ccxConfigDir.resolve("profiles/" + name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("profile.yaml"), yaml);
    }

    @Nested
    @DisplayName("enable()")
    class Enable {

        @Test
        @DisplayName("should apply the whole extends closure and report every member as applied")
        void enable_dependent_appliesClosure() throws Exception {
            var result = service.enable("java-cli", projectDir);

            assertThat(result.applied())
                .as("enabling java-cli must apply its dependency java too, dependency first")
                .containsExactly("java", "java-cli");
            assertThat(settingsService.read(projectDir).enabledPlugins())
                .as("both profiles' plugins must land in settings.json")
                .containsKeys("java@mkt", "java-cli@mkt");
            assertThat(ccxContextService.read(projectDir).enabledProfiles())
                .as("both members must be recorded enabled in the context file")
                .containsKeys("java", "java-cli");
        }

        @Test
        @DisplayName("should report an already-enabled member instead of re-applying it")
        void enable_alreadyEnabledMember_isReported() throws Exception {
            service.enable("java", projectDir);

            var result = service.enable("java-cli", projectDir);

            assertThat(result.alreadyEnabled())
                .as("java was enabled by the first call, so the second must skip it")
                .containsExactly("java");
            assertThat(result.applied())
                .as("only the leaf remains to apply")
                .containsExactly("java-cli");
            assertThat(result.alreadyEnabledDependencies("java-cli"))
                .as("the skipped member is a dependency of the requested profile, not the profile itself")
                .containsExactly("java");
            assertThat(result.wasAlreadyEnabled("java-cli"))
                .as("java-cli itself was not already enabled")
                .isFalse();
        }

        @Test
        @DisplayName("should flag the requested profile as already enabled when it is")
        void enable_alreadyEnabledLeaf_isFlagged() throws Exception {
            service.enable("java", projectDir);

            var result = service.enable("java", projectDir);

            assertThat(result.wasAlreadyEnabled("java"))
                .as("re-enabling java must be reported as a no-op for java")
                .isTrue();
            assertThat(result.applied())
                .as("nothing may be re-applied")
                .isEmpty();
        }

        @Test
        @DisplayName("should report a dependency with no profile on disk without aborting the others")
        void enable_missingDependency_isReported() throws Exception {
            var result = service.enable("orphan", projectDir);

            assertThat(result.missingDependencies())
                .as("the extends target has no YAML on disk and must be reported")
                .containsExactly("nowhere");
            assertThat(result.applied())
                .as("the profile itself must still be applied")
                .contains("orphan");
        }

        @Test
        @DisplayName("should reject an unknown profile before writing anything")
        void enable_unknownProfile_throwsAndWritesNothing() {
            assertThatThrownBy(() -> service.enable("nope", projectDir))
                .as("an unknown profile must fail fast")
                .isInstanceOf(ProfileNotFoundException.class);

            assertThat(projectDir.resolve(".claude/settings.json"))
                .as("nothing may be written when the profile does not exist")
                .doesNotExist();
        }
    }

    @Nested
    @DisplayName("disable()")
    class Disable {

        @Test
        @DisplayName("should revert the snapshot and drop the profile from the context")
        void disable_enabledProfile_revertsIt() throws Exception {
            service.enable("java", projectDir);

            assertThat(service.disable("java", projectDir))
                .as("disabling a recorded profile must report that it did something")
                .isTrue();
            assertThat(settingsService.read(projectDir).enabledPlugins())
                .as("the profile's plugin must be removed from settings.json")
                .doesNotContainKey("java@mkt");
            assertThat(ccxContextService.read(projectDir).enabledProfiles())
                .as("the profile must no longer be recorded enabled")
                .doesNotContainKey("java");
        }

        @Test
        @DisplayName("should report false and touch nothing when the profile is not enabled")
        void disable_notEnabled_returnsFalse() throws Exception {
            assertThat(service.disable("java", projectDir))
                .as("disabling a profile that was never enabled must be a no-op")
                .isFalse();
            assertThat(projectDir.resolve(".claude/settings.json"))
                .as("a no-op disable may not create files")
                .doesNotExist();
        }
    }

    @Nested
    @DisplayName("reinstall()")
    class Reinstall {

        @Test
        @DisplayName("should restore an edited rule file the enable path leaves alone")
        void reinstall_editedRuleFile_isRestored() throws Exception {
            service.enable("java", projectDir);
            var ruleFile = projectDir.resolve(".claude/rules/java.md");
            Files.writeString(ruleFile, "clobbered\n");

            service.reinstall("java", projectDir);

            assertThat(ruleFile)
                .as("reinstall applies with overwrite=true, so the profile's content must come back")
                .hasContent("# Java rules\n");
            assertThat(ccxContextService.read(projectDir).enabledProfiles())
                .as("reinstall must leave the profile recorded enabled")
                .containsKey("java");
        }

        @Test
        @DisplayName("should apply a profile that was not enabled yet")
        void reinstall_notEnabled_appliesIt() throws Exception {
            service.reinstall("java-cli", projectDir);

            assertThat(settingsService.read(projectDir).enabledPlugins())
                .as("reinstall applies the fully resolved profile, inherited plugins included")
                .containsKeys("java@mkt", "java-cli@mkt");
        }
    }
}
