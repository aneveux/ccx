package me.neveux.ccx.tui;

/**
 * Mutable TUI session state owned by {@link CcxApp}. Tracks the active tab index,
 * the current {@link TuiSnapshot} (replaced after every mutation), help-overlay
 * visibility, per-tab selected row indices, filter-bar focus, and the Overview tab's
 * scroll offset. All mutation is confined to the render/event thread.
 */
public final class TuiState {

    private static final int TAB_COUNT = Tab.values().length;

    private int activeTab;
    private TuiSnapshot snapshot;
    private boolean helpVisible;
    private final int[] selectedRows = new int[TAB_COUNT];

    private boolean filterFocused = false;

    private int overviewMaxOffset = Integer.MAX_VALUE;

    private final FilterState[] filterStates = buildFilterStates();

    private static FilterState[] buildFilterStates() {
        var states = new FilterState[TAB_COUNT];
        for (var tab : Tab.values()) {
            states[tab.index()] = tab.filterable() ? new FilterState() : null;
        }
        return states;
    }

    public TuiState(TuiSnapshot snapshot) {
        this.snapshot = snapshot;
        this.activeTab = 0;
        this.helpVisible = false;
    }

    public int activeTab() {
        return activeTab;
    }

    public TuiSnapshot snapshot() {
        return snapshot;
    }

    public boolean helpVisible() {
        return helpVisible;
    }

    public void setSnapshot(TuiSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public void toggleHelp() {
        helpVisible = !helpVisible;
    }

    public void nextTab() {
        clearFocusOnCurrentFilter();
        activeTab = (activeTab + 1) % TAB_COUNT;
        filterFocused = false;
    }

    public void prevTab() {
        clearFocusOnCurrentFilter();
        activeTab = (activeTab - 1 + TAB_COUNT) % TAB_COUNT;
        filterFocused = false;
    }

    public int selectedRow() {
        return selectedRows[activeTab];
    }

    public void nextRow(int itemCount) {
        if (itemCount > 0 && selectedRows[activeTab] < itemCount - 1) {
            selectedRows[activeTab]++;
        }
    }

    public void prevRow() {
        if (selectedRows[activeTab] > 0) {
            selectedRows[activeTab]--;
        }
    }

    /**
     * Scroll the Overview tab (tab 0) by {@code delta} lines.
     * Uses selectedRows[0] as the scroll offset, clamped to {@code [0, overviewMaxOffset]}.
     * The upper bound is the last value published by {@link #setOverviewMaxOffset} during render.
     */
    public void scrollOverview(int delta) {
        selectedRows[0] = Math.max(0, Math.min(selectedRows[0] + delta, overviewMaxOffset));
    }

    /**
     * Records the maximum scroll offset of the last-rendered Overview frame and re-clamps the
     * current offset, so a viewport resize cannot strand the offset past the end of the content.
     */
    public void setOverviewMaxOffset(int maxOffset) {
        overviewMaxOffset = Math.max(0, maxOffset);
        selectedRows[0] = Math.min(selectedRows[0], overviewMaxOffset);
    }

    public FilterState currentFilter() { return filterStates[activeTab]; }

    public boolean hasFilter() { return filterStates[activeTab] != null; }

    public boolean filterFocused() { return filterFocused; }

    public void focusFilter() {
        filterFocused = true;
        if (filterStates[activeTab] != null) filterStates[activeTab].setFocused(true);
    }

    public void blurFilter() {
        filterFocused = false;
        if (filterStates[activeTab] != null) filterStates[activeTab].setFocused(false);
    }

    public void setTab(int tab) {
        clearFocusOnCurrentFilter();
        activeTab = Math.max(0, Math.min(tab, TAB_COUNT - 1));
        filterFocused = false;
    }

    private void clearFocusOnCurrentFilter() {
        if (filterStates[activeTab] != null) filterStates[activeTab].setFocused(false);
    }
}
