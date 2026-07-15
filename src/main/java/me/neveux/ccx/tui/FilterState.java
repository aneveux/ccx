package me.neveux.ccx.tui;

import dev.tamboui.widgets.input.TextInputState;

import java.util.List;
import java.util.function.Function;

/**
 * Per-tab filter bar state. Wraps a Tamboui {@link dev.tamboui.widgets.input.TextInputState}
 * to track the current query text and cursor position, and adds selection tracking and
 * fuzzy-match filtering over an arbitrary list of items. Each filterable {@link Tab}
 * owns one instance held in {@link TuiState}.
 */
public final class FilterState {
    private final TextInputState inputState = new TextInputState();
    private int selectedIndex = 0;
    private boolean focused = false;

    public TextInputState inputState() { return inputState; }

    public boolean focused() { return focused; }

    public void setFocused(boolean focused) { this.focused = focused; }

    public String query() { return inputState.text(); }

    public boolean isEmpty() { return query().isEmpty(); }

    public int selectedIndex() { return selectedIndex; }

    public void setSelectedIndex(int i) { selectedIndex = i; }

    public void appendChar(char c) { inputState.insert(c); }

    public void backspace() { inputState.deleteBackward(); }

    public void clear() {
        inputState.clear();
        selectedIndex = 0;
    }

    public <T> List<T> filter(List<T> items, Function<T, String> label) {
        if (isEmpty()) return items;
        var q = query().toLowerCase();
        return items.stream()
            .filter(i -> fuzzyMatch(q, label.apply(i).toLowerCase()))
            .toList();
    }

    private static boolean fuzzyMatch(String query, String text) {
        int qi = 0;
        for (int i = 0; i < text.length() && qi < query.length(); i++) {
            if (text.charAt(i) == query.charAt(qi)) qi++;
        }
        return qi == query.length();
    }

    public <T> int clampedIndex(List<T> filtered) {
        return Math.min(selectedIndex, Math.max(0, filtered.size() - 1));
    }
}
