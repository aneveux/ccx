// Wave 8A — full production-CDI-path E2E scenario (characterization / regression guard)
package me.neveux.ccx.cli;

import me.neveux.ccx.service.CcxConfigService;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 8A — the end-to-end regression guard for the whole "tests stay green while the binary is
 * broken" bug class.
 *
 * <p>Every command here is driven through the REAL production {@code CommandLine.IFactory} CDI bean
 * (the exact bean injected into {@code Main}), mirroring {@code Main.run}:
 * {@code new CommandLine(new CcxCommand(), factory).execute(args)}. It does NOT use
 * {@code TestCommandFactory}. The command beans, their {@code @Dependent}/{@code @ApplicationScoped}
 * scopes, and picocli {@code @Spec}/{@code @Option} injection — the actual bug surface — are exercised
 * exactly as in production.
 *
 * <p>The only seam is the config/profiles directory: {@link CcxConfigService} resolves it from a
 * constructor override or {@code XDG_CONFIG_HOME}. Process env cannot be mutated under
 * {@code @QuarkusTest}, so we install a REAL {@code CcxConfigService} pointed at a {@code @TempDir}
 * via {@link QuarkusMock}. Every service that reads config is injected with that same bean, so the
 * redirect propagates without touching any command scope.
 *
 * <p>This is a characterization test: Waves 1–7 are implemented, so it must pass GREEN. A failing
 * assertion here is a surfaced latent bug, not a test to weaken.
 */
@QuarkusTest
@DisplayName("Production CDI path: full profile/claude-md/doctor/status scenario")
class FullScenarioE2ETest {

    @Inject
    CommandLine.IFactory factory;

    // @TempDir field injection does not run before @BeforeEach under the QuarkusTest extension
    // (the temp dirs are null at that point), so the temp dirs are created manually here and
    // cleaned up in @AfterEach — same idiom as ProductionPathE2ETest.
    private Path configDir;
    private Path projectDir;
    private Path emptyGlobalDir;

    @BeforeEach
    void scaffold() throws IOException {
        configDir = Files.createTempDirectory("ccx-e2e-config");
        projectDir = Files.createTempDirectory("ccx-e2e-project");
        emptyGlobalDir = Files.createTempDirectory("ccx-e2e-global");

        // Redirect ALL config-reading services at the real CcxConfigService pointed at a temp dir.
        // This leaves the command beans (the bug surface) untouched — only the config seam moves.
        QuarkusMock.installMockForType(new CcxConfigService(configDir), CcxConfigService.class);

        var profilesDir = configDir.resolve("profiles");

        // Profile `java`: extends [], enables one plugin, declares stacks + a rule.
        var javaDir = profilesDir.resolve("java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("profile.yaml"), """
            metadata:
              name: java
              description: Java profile
            extends: []
            plugins:
              enable:
                - java@claude-java-stack
            claudeMd:
              stacks:
                - java
            rules:
              files:
                - java
            """);

        // Profile `java-functional`: extends [java].
        var functionalDir = profilesDir.resolve("java-functional");
        Files.createDirectories(functionalDir);
        Files.writeString(functionalDir.resolve("profile.yaml"), """
            metadata:
              name: java-functional
              description: Functional Java profile (extends java)
            extends:
              - java
            plugins:
              enable:
                - java-functional@claude-java-stack
            """);
    }

    @AfterEach
    void cleanup() throws IOException {
        for (var root : new Path[] {configDir, projectDir, emptyGlobalDir}) {
            if (root == null || !Files.exists(root)) continue;
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort temp cleanup
                    }
                });
            }
        }
    }

    /** Run one command through the production IFactory, capturing stdout+stderr into one buffer. */
    private CapturedRun run(String... args) {
        var buffer = new ByteArrayOutputStream();
        var writer = new PrintWriter(buffer, true, StandardCharsets.UTF_8);
        var cl = new CommandLine(new CcxCommand(), factory);
        cl.setOut(writer);
        cl.setErr(writer);
        int exit = cl.execute(args);
        writer.flush();
        return new CapturedRun(exit, buffer.toString(StandardCharsets.UTF_8));
    }

    private record CapturedRun(int exitCode, String output) {}

    private String proj() {
        return projectDir.toString();
    }

    @Test
    @DisplayName("profiles list should exit 0 and show both profiles when run through the production factory")
    void profilesList_throughProductionFactory_listsBothProfiles() {
        // `profiles list` reads only the global profiles dir (no --project-dir option exists).
        var result = run("profiles", "list");

        assertThat(result.exitCode())
            .as("profiles list must exit 0 on the production path")
            .isZero();
        assertThat(result.output())
            .as("profiles list (plain stdout, no TTY) must show both scaffolded profiles")
            .contains("java")
            .contains("java-functional");
    }

    @Test
    @DisplayName("enabling java-functional should auto-enable its java dependency and bootstrap .claudeignore")
    void enableJavaFunctional_autoEnablesParent_andBootstrapsClaudeIgnore() {
        var enable = run("profiles", "enable", "java-functional", "--project-dir", proj());

        assertThat(enable.exitCode())
            .as("enable java-functional must exit 0")
            .isZero();
        assertThat(enable.output())
            .as("enable must confirm the requested profile")
            .contains("Enabled profile: java-functional");

        var status = run("profiles", "status", "--project-dir", proj());

        assertThat(status.exitCode())
            .as("profiles status must exit 0")
            .isZero();
        assertThat(status.output())
            .as("Wave 6A auto-enable records the parent `java` too — both rows must be ENABLED")
            .contains("[ENABLED] java")
            .contains("[ENABLED] java-functional");

        assertThat(projectDir.resolve(".claudeignore"))
            .as("Wave 2A bootstrap must create .claudeignore on first enable")
            .exists();
    }

    @Test
    @DisplayName("disabling a dependency should mark its dependent BROKEN in status")
    void disableDependency_marksDependentBroken() {
        assertThat(run("profiles", "enable", "java-functional", "--project-dir", proj()).exitCode())
            .as("precondition: enable java-functional succeeds")
            .isZero();

        var disable = run("profiles", "disable", "java", "--project-dir", proj());

        assertThat(disable.exitCode())
            .as("disable java must exit 0")
            .isZero();
        assertThat(disable.output())
            .as("disable must confirm the removed profile")
            .contains("Disabled profile: java");

        var status = run("profiles", "status", "--project-dir", proj());

        assertThat(status.exitCode())
            .as("profiles status must still exit 0 with a broken dependent")
            .isZero();
        assertThat(status.output())
            .as("Wave 6 — disabling the `java` dependency must mark `java-functional` BROKEN "
                + "in the `[<STATE>] <name>` row format")
            .contains("[BROKEN] java-functional");
    }

    @Test
    @DisplayName("doctor should exit non-zero with a real missing-dependency warning when a dependent is broken")
    void doctor_reportsMissingDependency_withRealOutput() {
        assertThat(run("profiles", "enable", "java-functional", "--project-dir", proj()).exitCode())
            .as("precondition: enable java-functional succeeds")
            .isZero();
        assertThat(run("profiles", "disable", "java", "--project-dir", proj()).exitCode())
            .as("precondition: disable java succeeds")
            .isZero();

        var doctor = run("doctor", "--project-dir", proj());

        assertThat(doctor.output())
            .as("doctor must produce real output (proves @Spec non-null on the production path "
                + "— Wave 1 regression guard), never empty and never an NPE")
            .isNotBlank()
            .contains("[WARNING]")
            .contains("profile java-functional has missing dependency: java");
        assertThat(doctor.exitCode())
            .as("doctor must exit non-zero when a profile has a missing dependency")
            .isNotZero();
    }

    @Test
    @DisplayName("claude-md add/list/remove should round-trip a global fragment through the production factory")
    void claudeMd_addListRemove_roundTrips() throws IOException {
        var fragmentName = "java-extra";
        var fragmentFile = configDir.resolve("claudemd").resolve(fragmentName + ".md");
        Files.createDirectories(fragmentFile.getParent());
        Files.writeString(fragmentFile, "# Extra Java guidance\n");

        var add = run("claude-md", "add", fragmentName, "--project-dir", proj());
        assertThat(add.exitCode())
            .as("claude-md add must exit 0 when the fragment exists")
            .isZero();
        assertThat(add.output())
            .as("claude-md add must confirm the added fragment line")
            .contains("Added:")
            .contains(fragmentName);

        var listAfterAdd = run("claude-md", "list", "--project-dir", proj());
        assertThat(listAfterAdd.exitCode())
            .as("claude-md list must exit 0")
            .isZero();
        assertThat(listAfterAdd.output())
            .as("claude-md list must reference the added fragment")
            .contains(fragmentName);

        var remove = run("claude-md", "remove", fragmentName, "--project-dir", proj());
        assertThat(remove.exitCode())
            .as("claude-md remove must exit 0")
            .isZero();
        assertThat(remove.output())
            .as("claude-md remove must confirm the removal")
            .contains("Removed:")
            .contains(fragmentName);

        var listAfterRemove = run("claude-md", "list", "--project-dir", proj());
        assertThat(listAfterRemove.exitCode())
            .as("claude-md list must exit 0 after removal")
            .isZero();
        assertThat(listAfterRemove.output())
            .as("claude-md list must no longer reference the removed fragment")
            .doesNotContain(fragmentName);
    }

    @Test
    @DisplayName("status --full should add a Global section that plain status omits")
    void statusFull_addsGlobalSection_thatPlainStatusOmits() {
        var plain = run("status", "--project-dir", proj());
        assertThat(plain.exitCode())
            .as("plain status must exit 0")
            .isZero();
        assertThat(plain.output())
            .as("plain (project-only) status must NOT contain the Global section header")
            .doesNotContain("=== Global ===");

        var full = run("status", "--full", "--project-dir", proj(),
            "--global-dir", emptyGlobalDir.toString());
        assertThat(full.exitCode())
            .as("status --full must exit 0")
            .isZero();
        assertThat(full.output())
            .as("Wave 7B — status --full must render the Global section header")
            .contains("=== Global ===");
    }
}
