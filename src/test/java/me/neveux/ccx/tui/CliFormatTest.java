package me.neveux.ccx.tui;

import me.neveux.ccx.cli.CliFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CliFormat")
class CliFormatTest {

    @Nested
    @DisplayName("isTty()")
    class IsTty {

        @Test
        @DisplayName("returns false in test / CI environment (no console attached)")
        void isTty_returnsFalse_inTestEnvironment() {
            // Tests always run without a TTY console, or with CI=1 set.
            // Either condition makes isTty() return false.
            assertThat(CliFormat.isTty())
                .as("isTty() must return false in a test/CI environment")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("glyph delegation to Glyphs constants")
    class GlyphDelegation {

        @Test
        @DisplayName("ENABLED constant equals Glyphs.ENABLED")
        void enabled_equalsGlyphs() {
            assertThat(CliFormat.ENABLED)
                .as("CliFormat.ENABLED must delegate to Glyphs.ENABLED")
                .isEqualTo(Glyphs.ENABLED);
        }

        @Test
        @DisplayName("GLOBAL constant equals Glyphs.GLOBAL")
        void global_equalsGlyphs() {
            assertThat(CliFormat.GLOBAL)
                .as("CliFormat.GLOBAL must delegate to Glyphs.GLOBAL")
                .isEqualTo(Glyphs.GLOBAL);
        }

        @Test
        @DisplayName("AVAILABLE constant equals Glyphs.AVAILABLE")
        void available_equalsGlyphs() {
            assertThat(CliFormat.AVAILABLE)
                .as("CliFormat.AVAILABLE must delegate to Glyphs.AVAILABLE")
                .isEqualTo(Glyphs.AVAILABLE);
        }

    }

    @Nested
    @DisplayName("fg() colour helper")
    class FgColorHelper {

        @Test
        @DisplayName("fg returns a string containing the original text when isTty is false")
        void fg_containsOriginalText_whenNoTty() {
            // When isTty() is false the helper returns the plain string unmodified.
            // Call green() as a representative colour helper.
            var result = CliFormat.green("hello");

            assertThat(result)
                .as("colour helper must embed the original text in its output")
                .contains("hello");
        }

        @Test
        @DisplayName("green('world') returns 'world' exactly when not a TTY (plain passthrough)")
        void green_returnsPlain_whenNoTty() {
            assertThat(CliFormat.green("world"))
                .as("green() must return the plain string when isTty() is false")
                .isEqualTo("world");
        }
    }
}
