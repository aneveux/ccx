package me.neveux.ccx.tui.tabs;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import me.neveux.ccx.tui.CatppuccinTheme;
import me.neveux.ccx.tui.Glyphs;
import me.neveux.ccx.tui.Quips;
import me.neveux.ccx.tui.TuiSnapshot;

import java.util.List;
import java.util.function.Function;

/**
 * TUI tab for Claude Code plugin management (tab 3). Lists all plugins across three
 * scopes — project-enabled, globally-enabled, and available from the marketplace —
 * colour-coded by their {@link TuiSnapshot.PluginStatus}. Press Enter to toggle a
 * project-scoped plugin; global plugins are read-only from the project TUI.
 */
public final class PluginsTab extends FilterableTab<TuiSnapshot.PluginEntry> {

    @Override protected String tabTitle() { return "Plugins"; }

    @Override protected String previewTitle() { return "Plugin Details"; }

    @Override protected List<TuiSnapshot.PluginEntry> items(TuiSnapshot snap) { return snap.plugins(); }

    @Override protected Function<TuiSnapshot.PluginEntry, String> filterKey() { return TuiSnapshot.PluginEntry::qualifiedName; }

    @Override protected Line listLabelLine(TuiSnapshot.PluginEntry p, TuiSnapshot snap) {
        var displayName = p.marketplace().isEmpty()
            ? p.shortName()
            : p.shortName() + "@" + p.marketplace();
        var color = switch (p.status()) {
            case PROJECT_ENABLED  -> CatppuccinTheme.TEAL;
            case GLOBALLY_ENABLED -> CatppuccinTheme.BLUE;
            case AVAILABLE        -> CatppuccinTheme.SUBTEXT;
        };
        return Line.from(
            Span.styled(scopeLabel(p.status()), Style.EMPTY.fg(color)),
            Span.raw(displayName)
        );
    }

    @Override protected String preview(TuiSnapshot.PluginEntry p, TuiSnapshot snap) {
        var status = switch (p.status()) {
            case PROJECT_ENABLED  -> "[P] project-enabled";
            case GLOBALLY_ENABLED -> "[G] globally-enabled";
            case AVAILABLE        -> "[·] available (not enabled)";
        };
        var desc = p.description() != null && !p.description().isBlank()
            ? "_" + p.description() + "_\n\n"
            : "";
        var globalNote = p.status() == TuiSnapshot.PluginStatus.GLOBALLY_ENABLED
            ? "_" + Glyphs.WARNING + " Global plugins cannot be toggled from the project TUI_\n\n" : "";
        return "## " + p.shortName() + (p.marketplace().isEmpty() ? "" : "@" + p.marketplace()) + "\n\n"
            + desc
            + "### Qualified name\n`" + p.qualifiedName() + "`\n\n"
            + "### Marketplace\n" + (p.marketplace().isEmpty() ? "_local_" : p.marketplace()) + "\n\n"
            + "### Status\n" + status + "\n\n"
            + globalNote
            + "_Press **Enter** to toggle · **/** to filter_";
    }

    @Override protected String emptyStateMessage(TuiSnapshot snap) {
        if (snap.plugins().isEmpty()) {
            return Glyphs.INFO + " no plugins discovered\n\n"
                + "_Add plugins via `.claude/settings.json` or enable a profile_";
        }
        return Quips.EMPTY[0] + " nothing matches — Esc to clear";
    }

    private static String scopeLabel(TuiSnapshot.PluginStatus status) {
        return switch (status) {
            case PROJECT_ENABLED   -> Glyphs.ENABLED + " ";
            case GLOBALLY_ENABLED  -> Glyphs.GLOBAL + " ";
            case AVAILABLE         -> Glyphs.AVAILABLE + " ";
        };
    }
}
