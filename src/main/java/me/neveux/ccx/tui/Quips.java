package me.neveux.ccx.tui;

/**
 * Flavor text and kaomoji constants for empty states, action confirmations, and error
 * banners. Keeps all human-visible quips in one place so they can be tuned without
 * hunting through rendering code. Use {@link #pick} for a deterministic selection
 * that avoids random flicker across renders.
 */
public final class Quips {
    private Quips() {}

    public static final String[] ENABLE  = {"✧˖°", "~(˘▽˘~)", "ᕕ(ᐛ)ᕗ"};
    public static final String[] DISABLE = {"・・・", "○"};
    public static final String[] REINSTALL = {"◉ hang on...", "ᕕ(ᐛ)ᕗ"};
    public static final String[] TOGGLE  = {"✓", "~"};
    public static final String[] ADD     = {"✧˖°", "◉"};
    public static final String[] REMOVE  = {"✓", "・"};
    public static final String[] ERROR   = {"(╯°□°)╯︵ ┻━┻", "ヽ(°〇°)ﾉ"};
    public static final String[] EMPTY   = {"(⌒_⌒;)", "¯\\_(ツ)_/¯"};
    public static final String[] ALLGOOD = {"(◕‿◕✿)", "ᕕ(ᐛ)ᕗ", "✧˖°"};
    public static final String[] IMPOSSIBLE = {"ლ(ಠ益ಠ)ლ", "(╯°□°)╯︵ ┻━┻", "ヾ(｀ヘ´)ﾉﾞ"};

    /** Deterministic pick — same seed always returns the same quip. */
    public static String pick(String[] category, int seed) {
        return category[Math.floorMod(seed, category.length)];
    }

}
