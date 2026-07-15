// Wave 1 — CLI scope/help regression guards [TDD baseline]
package me.neveux.ccx.cli;

import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 1 — production-CDI-path regression guard.
 *
 * <p>This is THE test that catches the {@code @ApplicationScoped} proxy bug. It drives a command
 * through the REAL Quarkus {@code CommandLine.IFactory} (the CDI bean injected into {@code Main}),
 * NOT the {@code TestCommandFactory}. {@code @QuarkusMainTest} boots the application and invokes
 * {@code Main.run(args)} — the exact production entrypoint — via {@link QuarkusMainLauncher}.
 *
 * <p>Bug being guarded: commands annotated {@code @ApplicationScoped} get an ARC client proxy.
 * Picocli's {@code @Spec}/{@code @Option} field injection lands on the proxy, not the instance
 * that actually executes, so {@code spec} is null. {@code DoctorCommand.call()} then NPEs on
 * {@code spec.commandLine().getOut()} → no real doctor output / non-zero exit. After Wave 1
 * changes the scope to {@code @Dependent}, the executed instance receives a non-null {@code spec}
 * and produces real output.
 *
 * <p>{@code @TempDir} cannot be used directly because Quarkus launches the app in its own context;
 * the temp project dir is created in {@code @BeforeEach}, passed as {@code --project-dir}, and
 * cleaned up in {@code @AfterEach}. An empty project dir is valid: doctor exits 0 and emits
 * {@code [OK] settings.json absent} plus several {@code [INFO]} lines.
 */
@QuarkusMainTest
@DisplayName("Production CDI path: doctor must produce real output (regression guard for null @Spec)")
class ProductionPathE2ETest {

    private Path projectDir;

    @BeforeEach
    void createProjectDir() throws IOException {
        projectDir = Files.createTempDirectory("ccx-prod-path-e2e");
    }

    @AfterEach
    void deleteProjectDir() throws IOException {
        if (projectDir == null || !Files.exists(projectDir)) return;
        try (var paths = Files.walk(projectDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        }
    }

    @Test
    @DisplayName("doctor on an empty project should exit 0 and emit a real doctor line via the CDI factory")
    void doctor_throughProductionFactory_producesRealOutput(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("doctor", "--project-dir", projectDir.toString());

        // Combine stdout + stderr: an NPE from a null spec would surface output (or its absence)
        // on either stream depending on how the failure unwinds.
        var combined = result.getOutput() + result.getErrorOutput();

        assertThat(combined)
            .as("doctor must emit a real check line ([OK]/[INFO]), proving @Spec was non-null and "
                + "output was produced through the production CDI factory")
            .containsAnyOf("[OK]", "[INFO]");
        assertThat(result.exitCode())
            .as("doctor on a valid (empty) project must exit 0 through the production path")
            .isZero();
    }
}
