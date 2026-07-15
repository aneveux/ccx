package me.neveux.ccx.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Glyphs — single source of truth for terminal-safe status emoji")
class GlyphsTest {

    @Nested
    @DisplayName("status palette")
    class Palette {

        @Test
        @DisplayName("maps each meaning to the agreed emoji (Hearts palette)")
        void constants_areTheExpectedEmoji() {
            assertThat(Glyphs.ENABLED).isEqualTo("💚");
            assertThat(Glyphs.GLOBAL).isEqualTo("💙");
            assertThat(Glyphs.AVAILABLE).isEqualTo("🤍");
            assertThat(Glyphs.DRIFTED).isEqualTo("💛");
            assertThat(Glyphs.BROKEN).isEqualTo("💔");
            assertThat(Glyphs.WARNING).isEqualTo("🚧");
            assertThat(Glyphs.OK).isEqualTo("✅");
            assertThat(Glyphs.ERROR).isEqualTo("❌");
            assertThat(Glyphs.INFO).isEqualTo("💡");
            assertThat(Glyphs.BRAND).isEqualTo("🌿");
        }

        @Test
        @DisplayName("contains no nerd-font private-use codepoints nor legacy geometric glyphs")
        void constants_haveNoLegacyGlyphs() {
            var all = List.of(
                Glyphs.ENABLED, Glyphs.GLOBAL, Glyphs.AVAILABLE, Glyphs.DRIFTED,
                Glyphs.BROKEN, Glyphs.WARNING, Glyphs.OK, Glyphs.ERROR, Glyphs.INFO, Glyphs.BRAND);
            for (var g : all) {
                assertThat(g).as("glyph %s must not be a legacy geometric mark", g)
                    .doesNotContain("●").doesNotContain("○").doesNotContain("◎")
                    .doesNotContain("◉").doesNotContain("◈").doesNotContain("↗")
                    .doesNotContain("✗").doesNotContain("✓").doesNotContain("·")
                    .doesNotContain("◐");
                g.codePoints().forEach(cp ->
                    assertThat(cp >= 0xE000 && cp <= 0xF8FF)
                        .as("glyph %s must not use a BMP private-use (nerd-font) codepoint", g)
                        .isFalse());
            }
        }
    }

    @Nested
    @DisplayName("width-safe palette (plan 038 glitch fix)")
    class WidthSafe {

        private static List<String> allStatusGlyphs() {
            return List.of(
                Glyphs.ENABLED, Glyphs.DRIFTED, Glyphs.BROKEN, Glyphs.GLOBAL, Glyphs.AVAILABLE,
                Glyphs.WARNING, Glyphs.OK, Glyphs.ERROR, Glyphs.INFO, Glyphs.BRAND);
        }

        @Test
        @DisplayName("every status marker renders as exactly two columns")
        void everyMarker_isTwoColumns() {
            for (var g : allStatusGlyphs()) {
                assertThat(Glyphs.displayWidth(g))
                    .as("status marker %s must occupy two terminal columns", g)
                    .isEqualTo(2);
            }
        }

        // THE regression guard: tamboui's CharWidth table has a gap [0x1F7E0..0x1F7EB]
        // (the coloured geometric circles/squares) where it reports width-1 but real
        // terminals render width-2 — that desync is the cursor/doubled-text glitch.
        // 🟢 U+1F7E2 and 🟡 U+1F7E1 fall in that gap; ⚠️ is the bare warning sign U+26A0.
        // These MUST fail today and pass once the Hearts palette lands.
        @Test
        @DisplayName("no status marker's first codepoint sits in the broken tamboui width-gap, nor is it the bare warning sign")
        void noMarker_inWidthGap_norBareWarningSign() {
            for (var g : allStatusGlyphs()) {
                int cp = g.codePointAt(0);
                assertThat(cp >= 0x1F7E0 && cp <= 0x1F7EB)
                    .as("status marker %s (U+%04X) must NOT use a tamboui width-gap codepoint [0x1F7E0..0x1F7EB]", g, cp)
                    .isFalse();
                assertThat(cp)
                    .as("status marker %s must NOT be the bare warning sign U+26A0 (renders width-1 in tamboui)", g)
                    .isNotEqualTo(0x26A0);
            }
        }
    }

    @Nested
    @DisplayName("display width")
    class Width {

        @Test
        @DisplayName("counts an emoji status marker as two columns")
        void displayWidth_emoji_isTwo() {
            assertThat(Glyphs.displayWidth("💚")).isEqualTo(2);
            assertThat(Glyphs.displayWidth(Glyphs.BRAND)).isEqualTo(2);
        }

        @Test
        @DisplayName("counts plain ASCII as one column per char")
        void displayWidth_ascii_isLength() {
            assertThat(Glyphs.displayWidth("ccx")).isEqualTo(3);
            assertThat(Glyphs.displayWidth("")).isZero();
        }

        @Test
        @DisplayName("counts a mixed emoji + ASCII string with the emoji as two")
        void displayWidth_mixed_countsEmojiAsTwo() {
            assertThat(Glyphs.displayWidth("🌿 ccx")).isEqualTo(6);
        }
    }

    @Nested
    @DisplayName("footer padding")
    class FooterPadding {

        @Test
        @DisplayName("never returns a negative pad, even when content overflows the width")
        void footerPadding_clampsAtZero() {
            assertThat(Glyphs.footerPadding(5, "🌿 ccx 0.1.0  ? Help", "💡 no cache"))
                .isGreaterThanOrEqualTo(0);
            assertThat(Glyphs.footerPadding(0, "🌿", "💡")).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("accounts for emoji as two columns when computing the gap")
        void footerPadding_usesDisplayWidth() {
            // width 20, left "🌿" (2 cols), right "💡" (2 cols) → gap 16, not 18
            assertThat(Glyphs.footerPadding(20, "🌿", "💡")).isEqualTo(16);
        }
    }
}
