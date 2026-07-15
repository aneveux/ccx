package me.neveux.ccx.tui;

/**
 * The fixed TUI tab order (ADR-007). Single source of truth for tab identity,
 * title, and whether the tab carries a filter bar — replacing the scattered
 * {@code TAB_*} int constants in {@link CcxApp} and the bare-literal array in
 * {@link TuiState}.
 */
public enum Tab {
    OVERVIEW("📊 1 - Overview", false),
    PROFILES("🎭 2 - Profiles", true),
    PLUGINS("🧩 3 - Plugins", true),
    MCP("🔗 4 - MCPs", true),
    CLAUDEMD("📝 5 - Claude.md", true);

    private final String title;
    private final boolean filterable;

    Tab(String title, boolean filterable) {
        this.title = title;
        this.filterable = filterable;
    }

    public String title() {
        return title;
    }

    public boolean filterable() {
        return filterable;
    }

    public int index() {
        return ordinal();
    }

    public static Tab of(int index) {
        return values()[index];
    }
}
