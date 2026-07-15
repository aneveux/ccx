package me.neveux.ccx.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("CommandUtil")
class CommandUtilTest {

    @Test
    @DisplayName("resolveGlobalDir: should return the provided path when it is non-null")
    void resolveGlobalDir_returnsProvidedPath_whenNonNull() {
        var knownPath = Path.of("/some/known/path");

        var result = CommandUtil.resolveGlobalDir(knownPath);

        assertThat(result).isEqualTo(knownPath);
    }

    @Test
    @DisplayName("resolveGlobalDir: should return ~/.claude when null is provided")
    void resolveGlobalDir_returnsHomeClaudeDir_whenNull() {
        var expected = Path.of(System.getProperty("user.home")).resolve(".claude");

        var result = CommandUtil.resolveGlobalDir(null);

        assertThat(result).isEqualTo(expected);
    }

    // -----------------------------------------------------------------------
    // Plan 040 Item 28 — additional guards
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("resolveProjectDir: returns Path.of('.') when null is provided")
    void resolveProjectDir_nullArg_returnsCwd() {
        var result = CommandUtil.resolveProjectDir(null);

        assertThat(result)
            .as("resolveProjectDir(null) must return Path.of('.') (the current working directory)")
            .isEqualTo(Path.of("."));
    }

    @Test
    @DisplayName("checkPathSecurity: does not throw NPE when spec is null")
    void checkPathSecurity_nullSpec_doesNotThrow() {
        assertThatCode(() -> CommandUtil.checkPathSecurity(Path.of("/tmp/evil"), null, "--project-dir"))
            .as("checkPathSecurity must not throw NPE when the CommandSpec is null")
            .doesNotThrowAnyException();
    }
}
