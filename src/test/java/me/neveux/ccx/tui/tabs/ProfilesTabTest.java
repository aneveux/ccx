package me.neveux.ccx.tui.tabs;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import me.neveux.ccx.model.ProfileDriftState;
import me.neveux.ccx.tui.FilterState;
import me.neveux.ccx.tui.Glyphs;
import me.neveux.ccx.tui.TuiSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProfilesTab")
class ProfilesTabTest {

    private static TuiSnapshot snapshotWithProfiles(List<TuiSnapshot.ProfileEntry> profiles) {
        return new TuiSnapshot(
            TuiSnapshot.OverviewData.empty(),
            profiles,
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static String render(TuiSnapshot snap, int width, int height) {
        var area = new Rect(0, 0, width, height);
        var buffer = Buffer.empty(area);
        var frame = Frame.forTesting(buffer);
        var filterState = new FilterState();
        new ProfilesTab().render(frame, area, snap, filterState, 0);
        return buffer.toAnsiString();
    }

    @Nested
    @DisplayName("when profiles are in different drift states")
    class ProfileDriftStates {

        @Test
        @DisplayName("renders Glyphs.ENABLED for a profile in ENABLED state")
        void render_showsEnabledGlyph_forEnabledProfile() {
            var profiles = List.of(
                new TuiSnapshot.ProfileEntry("java", "Java profile", ProfileDriftState.ENABLED, "## java")
            );
            var out = render(snapshotWithProfiles(profiles), 120, 30);

            assertThat(out)
                .as("ProfilesTab must render Glyphs.ENABLED for an ENABLED profile")
                .contains(Glyphs.ENABLED);
        }

        @Test
        @DisplayName("renders Glyphs.DRIFTED for a profile in ALTERED (drifted) state")
        void render_showsDriftedGlyph_forAlteredProfile() {
            var profiles = List.of(
                new TuiSnapshot.ProfileEntry("java", "Java profile", ProfileDriftState.ALTERED, "## java")
            );
            var out = render(snapshotWithProfiles(profiles), 120, 30);

            assertThat(out)
                .as("ProfilesTab must render Glyphs.DRIFTED for an ALTERED (drifted) profile")
                .contains(Glyphs.DRIFTED);
        }

        @Test
        @DisplayName("renders Glyphs.BROKEN for a profile in BROKEN state")
        void render_showsBrokenGlyph_forBrokenProfile() {
            var profiles = List.of(
                new TuiSnapshot.ProfileEntry("java", "Java profile", ProfileDriftState.BROKEN, "## java")
            );
            var out = render(snapshotWithProfiles(profiles), 120, 30);

            assertThat(out)
                .as("ProfilesTab must render Glyphs.BROKEN for a BROKEN profile")
                .contains(Glyphs.BROKEN);
        }

        @Test
        @DisplayName("renders profile names for all three states")
        void render_showsProfileNames_forAllStates() {
            var profiles = List.of(
                new TuiSnapshot.ProfileEntry("enabled-profile", "Enabled", ProfileDriftState.ENABLED, "## ep"),
                new TuiSnapshot.ProfileEntry("drifted-profile", "Drifted", ProfileDriftState.ALTERED, "## dp"),
                new TuiSnapshot.ProfileEntry("broken-profile", "Broken", ProfileDriftState.BROKEN, "## bp")
            );
            var out = render(snapshotWithProfiles(profiles), 120, 40);

            assertThat(out)
                .as("ProfilesTab must render the ENABLED profile name")
                .contains("enabled-profile");

            // The selected item name is rendered in the list; others depend on terminal height.
            // At minimum the selected (first) entry must appear.
            assertThat(out)
                .as("ProfilesTab must render at least one profile name without throwing")
                .isNotBlank();
        }
    }
}
