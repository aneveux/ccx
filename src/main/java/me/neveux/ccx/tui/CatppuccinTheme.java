package me.neveux.ccx.tui;

import dev.tamboui.style.Color;

/**
 * The Catppuccin Mocha palette, kept complete on purpose: it is the single source
 * of design tokens for the TUI. Some swatches have no current call site but stay
 * here so new surfaces pick colours from the canonical palette instead of inlining
 * hex strings.
 */
public final class CatppuccinTheme {
    private CatppuccinTheme() {}

    public static final Color TEXT     = Color.hex("#cdd6f4");
    public static final Color SUBTEXT  = Color.hex("#a6adc8");
    public static final Color GREEN    = Color.hex("#a6e3a1");
    public static final Color YELLOW   = Color.hex("#f9e2af");
    public static final Color RED      = Color.hex("#f38ba8");
    public static final Color BLUE     = Color.hex("#89b4fa");
    public static final Color MAUVE    = Color.hex("#cba6f7");
    public static final Color PEACH    = Color.hex("#fab387");
    public static final Color TEAL     = Color.hex("#94e2d5");
    public static final Color SURFACE1  = Color.hex("#45475a");
    public static final Color SURFACE0  = Color.hex("#313244");
    public static final Color LAVENDER  = Color.hex("#b4befe");
    public static final Color SAPPHIRE  = Color.hex("#74c7ec");
    public static final Color PINK      = Color.hex("#f5c2e7");
    public static final Color FLAMINGO  = Color.hex("#f2cdcd");
}
