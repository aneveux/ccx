package me.neveux.ccx.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import me.neveux.ccx.model.Profile;
import me.neveux.ccx.model.ProfileClaudeMd;
import me.neveux.ccx.model.ProfileMetadata;
import me.neveux.ccx.model.ProfilePlugins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ProfileService")
class ProfileServiceTest {

    private ProfileService service;

    @BeforeEach
    void setUp() {
        service = new ProfileService();
    }

    // -----------------------------------------------------------------------
    // load() and list() from filesystem
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("load() and list() from filesystem")
    class WhenLoadingFromFilesystem {

        @TempDir
        Path tempDir;

        private ProfileService service;

        @BeforeEach
        void setUp() {
            var yamlMapper = new ObjectMapper(new YAMLFactory());
            yamlMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            yamlMapper.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
            var ccxConfig = new CcxConfigService(tempDir);
            service = new ProfileService(yamlMapper, ccxConfig);
        }

        @Test
        @DisplayName("should load profile from filesystem config dir")
        void loadProfile_loadsFromConfigDir() throws Exception {
            var profileDir = tempDir.resolve("profiles/test");
            Files.createDirectories(profileDir);
            Files.writeString(profileDir.resolve("profile.yaml"),
                "metadata:\n  name: test\n");

            var profile = service.load("test");

            assertThat(profile.metadata().name()).isEqualTo("test");
        }

        @Test
        @DisplayName("should throw ProfileNotFoundException when profile.yaml is missing in the dir")
        void loadProfile_throwsProfileNotFoundException_whenProfileYamlMissing() throws Exception {
            Files.createDirectories(tempDir.resolve("profiles/empty"));

            assertThatThrownBy(() -> service.load("empty"))
                .isInstanceOf(ProfileNotFoundException.class);
        }

        @Test
        @DisplayName("should throw ProfileNotFoundException when the profile directory does not exist")
        void loadProfile_throwsProfileNotFoundException_whenDirMissing() {
            assertThatThrownBy(() -> service.load("ghost"))
                .isInstanceOf(ProfileNotFoundException.class);
        }

        @Test
        @DisplayName("should return sorted profile names from filesystem")
        void listAvailableProfiles_returnsSortedNames() throws Exception {
            for (var name : List.of("beta", "alpha")) {
                var dir = tempDir.resolve("profiles/" + name);
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("profile.yaml"),
                    "metadata:\n  name: " + name + "\n");
            }

            var names = service.listAvailableProfiles();

            assertThat(names).containsExactly("alpha", "beta");
        }

        @Test
        @DisplayName("should return empty list when profiles dir does not exist")
        void listAvailableProfiles_returnsEmptyList_whenConfigDirAbsent() {
            var ccxConfig = new CcxConfigService(tempDir.resolve("nonexistent"));
            var yamlMapper = new ObjectMapper(new YAMLFactory());
            var isolated = new ProfileService(yamlMapper, ccxConfig);

            assertThat(isolated.listAvailableProfiles()).isEmpty();
        }

        @Test
        @DisplayName("should skip subdirs without profile.yaml in the listing")
        void listAvailableProfiles_skipsSubdirsWithoutProfileYaml() throws Exception {
            Files.createDirectories(tempDir.resolve("profiles/noYaml"));
            var dir = tempDir.resolve("profiles/hasYaml");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("profile.yaml"), "metadata:\n  name: hasYaml\n");

            var names = service.listAvailableProfiles();

            assertThat(names).containsExactly("hasYaml");
        }

        @Test
        @DisplayName("should merge parent and child plugins via extends when full registry is used")
        void loadAndResolve_extendsChainMergesPlugins_fixesBug() throws Exception {
            var parentDir = tempDir.resolve("profiles/parent");
            Files.createDirectories(parentDir);
            Files.writeString(parentDir.resolve("profile.yaml"),
                "metadata:\n  name: parent\nplugins:\n  enable:\n    - parentPlugin\n");

            var childDir = tempDir.resolve("profiles/child");
            Files.createDirectories(childDir);
            Files.writeString(childDir.resolve("profile.yaml"),
                "metadata:\n  name: child\nextends:\n  - parent\nplugins:\n  enable:\n    - childPlugin\n");

            var resolved = service.loadAndResolve("child");

            assertThat(resolved.plugins().enable()).containsExactly("parentPlugin", "childPlugin");
        }

        @Test
        @DisplayName("should throw ProfileNotFoundException when profile is missing from filesystem")
        void loadAndResolve_throwsProfileNotFoundException_whenProfileMissing() {
            assertThatThrownBy(() -> service.loadAndResolve("missing"))
                .isInstanceOf(ProfileNotFoundException.class);
        }
    }

    // -----------------------------------------------------------------------
    // resolve() — inheritance resolution
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("resolve() inheritance")
    class WhenResolvingInheritance {

        @Test
        @DisplayName("should apply ancestor plugins before child plugins in resolution order")
        void resolveAppliesAncestorPluginsBeforeChild() {
            var parent = profileWithPlugins("parent", List.of(), List.of("pluginA"));
            var child = profileWithPlugins("child", List.of("parent"), List.of("pluginB"));

            var registry = Map.of("parent", parent, "child", child);
            var resolved = service.resolve(child, registry);

            assertThat(resolved.plugins().enable())
                .as("parent's pluginA must appear before child's pluginB")
                .containsExactly("pluginA", "pluginB");
        }

        @Test
        @DisplayName("should throw ProfileCycleException when a -> b -> a cycle is detected")
        void resolveThrowsOnCycle() {
            var a = profileWithPlugins("a", List.of("b"), List.of("pluginA"));
            var b = profileWithPlugins("b", List.of("a"), List.of("pluginB"));

            var registry = Map.of("a", a, "b", b);

            assertThatThrownBy(() -> service.resolve(a, registry))
                .isInstanceOf(ProfileCycleException.class);
        }

        @Test
        @DisplayName("should not add any implicit base profile when extends is empty list")
        void noImplicitBaseProfileWhenExtendsEmpty() {
            var standalone = profileWithPlugins("standalone", List.of(), List.of("only-plugin"));

            var registry = Map.of("standalone", standalone);
            var resolved = service.resolve(standalone, registry);

            assertThat(resolved.plugins().enable())
                .as("profile with extends:[] must have ONLY its own plugins — no implicit base added")
                .containsExactly("only-plugin");
        }

        @Test
        @DisplayName("should not add any implicit base profile when extends is absent (null)")
        void noImplicitBaseProfileWhenExtendsAbsent() {
            var standalone = profileWithPlugins("standalone", null, List.of("only-plugin"));

            var registry = Map.of("standalone", standalone);
            var resolved = service.resolve(standalone, registry);

            assertThat(resolved.plugins().enable())
                .as("profile with no extends key must have ONLY its own plugins — no implicit base added")
                .containsExactly("only-plugin");
        }

        @Test
        @DisplayName("should merge plugins in grandparent → parent → child order for multi-level inheritance")
        void multiLevelInheritance_grandparent_to_child() {
            var grandparent = profileWithPlugins("grandparent", null, List.of("gpA"));
            var parent = profileWithPlugins("parent", List.of("grandparent"), List.of("pA"));
            var child = profileWithPlugins("child", List.of("parent"), List.of("cA"));

            var registry = Map.of("grandparent", grandparent, "parent", parent, "child", child);
            var resolved = service.resolve(child, registry);

            assertThat(resolved.plugins().enable())
                .as("multi-level inheritance must produce [gpA, pA, cA] in ancestor-first order")
                .containsExactly("gpA", "pA", "cA");
        }

        @Test
        @DisplayName("should throw ProfileNotFoundException when a parent profile is missing from the registry")
        void missingParent_throwsProfileNotFoundException() {
            var child = profileWithPlugins("child", List.of("ghost"), List.of("childPlugin"));

            var registry = Map.of("child", child);

            assertThatThrownBy(() -> service.resolve(child, registry))
                .as("resolve must throw ProfileNotFoundException when parent 'ghost' is absent")
                .isInstanceOf(ProfileNotFoundException.class)
                .hasMessageContaining("ghost");
        }
    }

    // -----------------------------------------------------------------------
    // claudeMd / stacks inheritance
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("resolve() fragments merging")
    class WhenMergingFragments {

        @Test
        @DisplayName("should merge fragments from parent and child into a single deduplicated list")
        void resolve_mergesFragmentsFromParent() {
            var parent = new Profile()
                .withMetadata(new ProfileMetadata("base", null))
                .withClaudeMd(new ProfileClaudeMd(List.of("java-intro")));

            var child = new Profile()
                .withMetadata(new ProfileMetadata("child", null))
                .withExtend(List.of("base"))
                .withClaudeMd(new ProfileClaudeMd(List.of("quarkus-intro")));

            var registry = Map.of("base", parent, "child", child);
            var resolved = service.resolve(child, registry);

            assertThat(resolved.claudeMd().fragments())
                .as("resolved fragments must contain both parent and child fragments")
                .containsExactlyInAnyOrder("java-intro", "quarkus-intro");
        }

        @Test
        @DisplayName("merging two profiles that both lack plugins yields a non-null empty enable list")
        void concatDedup_returnsEmptyList_whenBothInputsNull() {
            // An extends chain forces mergeProfiles -> mergePlugins(null, null) -> concatDedup(null, null).
            // A standalone profile would short-circuit before the merge and never exercise this path.
            var parent = new Profile()
                .withMetadata(new ProfileMetadata("base", null));
            var child = new Profile()
                .withMetadata(new ProfileMetadata("child", null))
                .withExtend(List.of("base"));

            var registry = Map.of("base", parent, "child", child);
            var resolved = service.resolve(child, registry);

            assertThat(resolved.plugins())
                .as("merge must always produce a ProfilePlugins, even when neither profile defines plugins")
                .isNotNull();
            assertThat(resolved.plugins().enable())
                .as("concatDedup(null, null) must produce a non-null EMPTY enable list — never null, never a phantom entry")
                .isNotNull()
                .isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // resolveDependencyClosure() — transitive extends closure (migrated from init)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("resolveDependencyClosure()")
    class WhenResolvingDependencyClosure {

        @TempDir
        Path tempDir;

        private ProfileService service;

        @BeforeEach
        void setUp() {
            var yamlMapper = new ObjectMapper(new YAMLFactory());
            yamlMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            yamlMapper.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
            service = new ProfileService(yamlMapper, new CcxConfigService(tempDir));
        }

        private void writeProfile(String name, String extendsList) throws Exception {
            var dir = tempDir.resolve("profiles/" + name);
            Files.createDirectories(dir);
            var yaml = "metadata:\n  name: " + name + "\n"
                + (extendsList == null ? "" : "extends:\n" + extendsList);
            Files.writeString(dir.resolve("profile.yaml"), yaml);
        }

        @Test
        @DisplayName("should return only the profile itself when it has no extends")
        void closure_standaloneProfile_returnsOnlyItself() throws Exception {
            writeProfile("java", null);

            var closure = service.resolveDependencyClosure("java");

            assertThat(closure)
                .as("a standalone profile's closure is just itself")
                .containsExactly("java");
        }

        @Test
        @DisplayName("should include transitive dependencies with the leaf last")
        void closure_includesTransitiveDepsLeafLast() throws Exception {
            writeProfile("java", null);
            writeProfile("java-functional", "  - java\n");

            var closure = service.resolveDependencyClosure("java-functional");

            assertThat(closure)
                .as("closure must include the parent and the leaf, parent before leaf")
                .containsExactly("java", "java-functional");
        }

        @Test
        @DisplayName("should resolve a multi-level chain in dependency-first order")
        void closure_multiLevelChain_dependencyFirst() throws Exception {
            writeProfile("base", null);
            writeProfile("java", "  - base\n");
            writeProfile("java-functional", "  - java\n");

            var closure = service.resolveDependencyClosure("java-functional");

            assertThat(closure)
                .as("grandparent → parent → leaf order")
                .containsExactly("base", "java", "java-functional");
        }

        @Test
        @DisplayName("should still include a missing dependency in the closure (for broken-dep detection)")
        void closure_includesMissingDependency() throws Exception {
            writeProfile("java-functional", "  - missing-parent\n");

            var closure = service.resolveDependencyClosure("java-functional");

            assertThat(closure)
                .as("a dependency with no profile.yaml on disk must still appear in the closure")
                .containsExactlyInAnyOrder("missing-parent", "java-functional");
        }

        @Test
        @DisplayName("should throw ProfileCycleException on a cyclic extends graph")
        void closure_throwsOnCycle() throws Exception {
            writeProfile("a", "  - b\n");
            writeProfile("b", "  - a\n");

            assertThatThrownBy(() -> service.resolveDependencyClosure("a"))
                .isInstanceOf(ProfileCycleException.class);
        }
    }

    // -----------------------------------------------------------------------
    // missingDependencies() — Wave 6 broken-dependency detection
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("missingDependencies()")
    class WhenComputingMissingDependencies {

        @TempDir
        Path tempDir;

        private ProfileService service;

        @BeforeEach
        void setUp() {
            var yamlMapper = new ObjectMapper(new YAMLFactory());
            yamlMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            yamlMapper.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
            service = new ProfileService(yamlMapper, new CcxConfigService(tempDir));
        }

        private void writeProfile(String name, String extendsList) throws Exception {
            var dir = tempDir.resolve("profiles/" + name);
            Files.createDirectories(dir);
            var yaml = "metadata:\n  name: " + name + "\n"
                + (extendsList == null ? "" : "extends:\n" + extendsList);
            Files.writeString(dir.resolve("profile.yaml"), yaml);
        }

        @Test
        @DisplayName("should return empty when every dependency in the closure is enabled")
        void missing_allDepsEnabled_returnsEmpty() throws Exception {
            writeProfile("java", null);
            writeProfile("java-functional", "  - java\n");

            var missing = service.missingDependencies("java-functional", Set.of("java", "java-functional"));

            assertThat(missing)
                .as("no dependency is missing when the parent is enabled")
                .isEmpty();
        }

        @Test
        @DisplayName("should return the dependency name when a transitive dep is not enabled")
        void missing_depDisabled_returnsIt() throws Exception {
            writeProfile("java", null);
            writeProfile("java-functional", "  - java\n");

            var missing = service.missingDependencies("java-functional", Set.of("java-functional"));

            assertThat(missing)
                .as("java is in the extends closure but not enabled → it is missing")
                .containsExactly("java");
        }

        @Test
        @DisplayName("should not count the profile itself as a missing dependency")
        void missing_excludesSelf() throws Exception {
            writeProfile("java", null);

            var missing = service.missingDependencies("java", Set.of("java"));

            assertThat(missing)
                .as("a profile is never its own missing dependency")
                .isEmpty();
        }

        @Test
        @DisplayName("should report a dependency that has no profile.yaml on disk as missing")
        void missing_depAbsentFromDisk_returnsIt() throws Exception {
            writeProfile("java-functional", "  - missing-parent\n");

            var missing = service.missingDependencies("java-functional", Set.of("java-functional"));

            assertThat(missing)
                .as("a dependency with no profile.yaml is still a missing dependency")
                .containsExactly("missing-parent");
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static Profile profileWithPlugins(String name, List<String> extendsProfiles, List<String> enabledPlugins) {
        var metadata = new ProfileMetadata(name, null);
        var plugins = new ProfilePlugins(enabledPlugins);

        var profile = new Profile()
            .withMetadata(metadata)
            .withExtend(extendsProfiles)
            .withPlugins(plugins);

        return profile;
    }
}
