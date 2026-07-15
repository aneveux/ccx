package me.neveux.ccx.cli;

/**
 * Shared CLI presentation tokens so every command speaks one visual language —
 * the same terminal-safe emoji the TUI tabs use (see {@link me.neveux.ccx.tui.Glyphs},
 * the single source of truth). Colour helpers stay for plain ANSI text segments; markers
 * are emoji that already carry their own colour. Colour is applied only on an interactive
 * TTY; piped or CI output stays plain so it can be parsed and asserted on.
 */
public final class CliFormat {
    private CliFormat() {}

    private static final String ESC = Character.toString(27);

    // Signature mark (matches the TUI footer brand).
    public static final String BRAND = me.neveux.ccx.tui.Glyphs.BRAND;

    // Health glyphs (status / doctor).
    public static final String OK    = me.neveux.ccx.tui.Glyphs.OK;
    public static final String WARN  = me.neveux.ccx.tui.Glyphs.WARNING;
    public static final String ERR   = me.neveux.ccx.tui.Glyphs.ERROR;
    public static final String INFO  = me.neveux.ccx.tui.Glyphs.INFO;

    // Scope glyphs (lists — mirror PluginsTab / ProfilesTab).
    public static final String ENABLED   = me.neveux.ccx.tui.Glyphs.ENABLED;
    public static final String GLOBAL    = me.neveux.ccx.tui.Glyphs.GLOBAL;
    public static final String AVAILABLE = me.neveux.ccx.tui.Glyphs.AVAILABLE;
    public static final String OFF       = me.neveux.ccx.tui.Glyphs.AVAILABLE;
    public static final String HIDDEN    = me.neveux.ccx.tui.Glyphs.DRIFTED;

    // Catppuccin Mocha swatches (hex mirrors CatppuccinTheme).
    private static final String GREEN   = "#a6e3a1";
    private static final String YELLOW  = "#f9e2af";
    private static final String RED     = "#f38ba8";
    private static final String TEAL    = "#94e2d5";
    private static final String SUBTEXT = "#a6adc8";

    /**
     * Returns {@code true} when stdout is connected to an interactive terminal
     * and the {@code CI} environment variable is not set to {@code 1}. Commands
     * use this to decide whether to emit ANSI colours or plain text.
     */
    public static boolean isTty() {
        return System.console() != null && !"1".equals(System.getenv("CI"));
    }

    /** Wraps {@code s} in the Catppuccin Mocha green ({@code #a6e3a1}) on a TTY, passthrough otherwise. */
    public static String green(String s)  { return fg(GREEN, s); }
    /** Wraps {@code s} in the Catppuccin Mocha yellow ({@code #f9e2af}) on a TTY, passthrough otherwise. */
    public static String yellow(String s) { return fg(YELLOW, s); }
    /** Wraps {@code s} in the Catppuccin Mocha red ({@code #f38ba8}) on a TTY, passthrough otherwise. */
    public static String red(String s)    { return fg(RED, s); }
    /** Wraps {@code s} in the Catppuccin Mocha teal ({@code #94e2d5}) on a TTY, passthrough otherwise. */
    public static String teal(String s)   { return fg(TEAL, s); }
    /** Wraps {@code s} in the Catppuccin Mocha subtext colour ({@code #a6adc8}) for de-emphasised text. */
    public static String dim(String s)    { return fg(SUBTEXT, s); }

    private static String fg(String hex, String s) {
        if (!isTty()) return s;
        int rgb = Integer.parseInt(hex.substring(1), 16);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return ESC + "[38;2;" + r + ";" + g + ";" + b + "m" + s + ESC + "[0m";
    }
}
