package me.neveux.ccx.tui;

/**
 * The single source of truth for ccx status markers (ADR-aligned, Wave 4). Terminal
 * emoji replace the earlier nerd-font/geometric glyphs so markers render in any modern
 * terminal without a patched font. Used by both the TUI tabs and the CLI commands.
 *
 * <p>Emoji are double-width — {@link #displayWidth(String)} and {@link #footerPadding}
 * exist so manual column maths (footer alignment) stays correct when a marker is present.
 */
public final class Glyphs {
    private Glyphs() {}

    public static final String ENABLED   = "💚";
    public static final String GLOBAL    = "💙";
    public static final String AVAILABLE = "🤍";
    public static final String DRIFTED   = "💛";
    public static final String BROKEN    = "💔";
    public static final String WARNING   = "🚧";
    public static final String OK        = "✅";
    public static final String ERROR     = "❌";
    public static final String INFO      = "💡";
    public static final String BRAND     = "🌿";

    /** Variation selector — zero-width; it only nudges the preceding glyph to emoji presentation. */
    private static final int VARIATION_SELECTOR_16 = 0xFE0F;

    /** Terminal display width, counting emoji as two columns and ASCII as one. */
    public static int displayWidth(String s) {
        if (s == null) return 0;
        int width = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == VARIATION_SELECTOR_16) continue;
            width += isEmoji(cp) ? 2 : 1;
        }
        return width;
    }

    /** Footer gap between left and right segments, emoji-aware and never negative. */
    public static int footerPadding(int width, String left, String right) {
        return Math.max(0, width - displayWidth(left) - displayWidth(right));
    }

    private static boolean isEmoji(int cp) {
        return (cp >= 0x1F300 && cp <= 0x1FAFF)  // pictographs, coloured circles, symbols
            || (cp >= 0x2600 && cp <= 0x27BF);   // misc symbols + dingbats (✅ ❌ ⚠ ⚪)
    }
}
