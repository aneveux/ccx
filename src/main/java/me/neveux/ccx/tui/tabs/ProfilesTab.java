package me.neveux.ccx.tui.tabs;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import me.neveux.ccx.model.ProfileDriftState;
import me.neveux.ccx.tui.CatppuccinTheme;
import me.neveux.ccx.tui.Glyphs;
import me.neveux.ccx.tui.TuiSnapshot;
import me.neveux.ccx.tui.Quips;

import java.util.List;
import java.util.function.Function;

/**
 * TUI tab for ccx profile management (tab 2). Lists all profiles discovered in
 * {@code ~/.config/ccx/profiles/}, colour-coded by their
 * {@link me.neveux.ccx.model.ProfileDriftState}. Press Enter to enable or disable the
 * selected profile; the preview pane shows plugins, fragments, rules, and permissions
 * declared by the profile.
 */
public final class ProfilesTab extends FilterableTab<TuiSnapshot.ProfileEntry> {

    @Override protected String tabTitle() { return "Profiles"; }
    @Override protected String previewTitle() { return "Profile Details"; }

    @Override protected List<TuiSnapshot.ProfileEntry> items(TuiSnapshot snap) { return snap.profiles(); }

    @Override protected Function<TuiSnapshot.ProfileEntry, String> filterKey() { return TuiSnapshot.ProfileEntry::name; }

    @Override protected Line listLabelLine(TuiSnapshot.ProfileEntry p, TuiSnapshot snap) {
        var color = switch (p.driftState()) {
            case ENABLED  -> CatppuccinTheme.GREEN;
            case ALTERED  -> CatppuccinTheme.YELLOW;
            case BROKEN   -> CatppuccinTheme.RED;
            case DISABLED -> CatppuccinTheme.SUBTEXT;
        };
        return Line.from(
            Span.styled(driftIcon(p.driftState()), Style.EMPTY.fg(color)),
            Span.raw(p.name())
        );
    }

    @Override protected String preview(TuiSnapshot.ProfileEntry p, TuiSnapshot snap) { return p.previewMarkdown(); }

    @Override protected String emptyStateMessage(TuiSnapshot snap) {
        if (snap.profiles().isEmpty()) {
            return Quips.EMPTY[0] + " no profiles configured\n\n"
                + "_Run `ccx profiles list` to see available profiles_";
        }
        return Quips.EMPTY[0] + " nothing matches — Esc to clear";
    }

    private static String driftIcon(ProfileDriftState state) {
        return switch (state) {
            case ENABLED  -> Glyphs.ENABLED + " ";
            case ALTERED  -> Glyphs.DRIFTED + " ";
            case BROKEN   -> Glyphs.BROKEN + " ";
            case DISABLED -> Glyphs.AVAILABLE + " ";
        };
    }
}
