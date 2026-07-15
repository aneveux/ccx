package me.neveux.ccx.tui.tabs;

import dev.tamboui.text.Line;
import me.neveux.ccx.tui.Glyphs;
import me.neveux.ccx.tui.Quips;
import me.neveux.ccx.tui.TuiSnapshot;

import java.util.List;
import java.util.function.Function;

/**
 * TUI tab for the CLAUDE.md fragment stack (tab 5). Lists all entries from the
 * CLAUDE.md marker block plus available but unreferenced fragments, colour-coded by
 * their {@link TuiSnapshot.StackSource}. The preview pane shows the fragment file
 * content or a diagnostic message for missing references.
 */
public final class ClaudeMdTab extends FilterableTab<TuiSnapshot.StackEntry> {

    @Override protected String tabTitle() { return "CLAUDE.md"; }
    @Override protected String previewTitle() { return "Fragment Details"; }

    @Override protected List<TuiSnapshot.StackEntry> items(TuiSnapshot snap) { return snap.claudeMdEntries(); }

    @Override protected Function<TuiSnapshot.StackEntry, String> filterKey() { return TuiSnapshot.StackEntry::name; }

    @Override protected Line listLabelLine(TuiSnapshot.StackEntry e, TuiSnapshot snap) {
        return Line.from(switch (e.source()) {
            case MISSING -> sourceIcon(e.source()) + e.name() + " (missing stack file)";
            default      -> sourceIcon(e.source()) + e.name();
        });
    }

    @Override protected String preview(TuiSnapshot.StackEntry e, TuiSnapshot snap) { return e.previewMarkdown(); }

    @Override protected String emptyStateMessage(TuiSnapshot snap) {
        if (snap.claudeMdEntries().isEmpty()) {
            return Glyphs.INFO + " no fragments found\n\n"
                + "_Global fragments: `~/.config/ccx/claudemd/`_\n\n"
                + "_Profile fragments: `~/.config/ccx/profiles/<name>/claudemd/`_\n\n"
                + "_Run `ccx claude-md list` to see available fragments_";
        }
        return Quips.EMPTY[0] + " nothing matches — Esc to clear";
    }

    private static String sourceIcon(TuiSnapshot.StackSource source) {
        return switch (source) {
            case PROJECT   -> Glyphs.ENABLED + " ";
            case FRAGMENT  -> Glyphs.GLOBAL + " ";
            case AVAILABLE -> Glyphs.AVAILABLE + " ";
            case MISSING   -> Glyphs.BROKEN + " ";
        };
    }
}
