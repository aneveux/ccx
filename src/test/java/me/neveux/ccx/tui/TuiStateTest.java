package me.neveux.ccx.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TuiState")
class TuiStateTest {

    private TuiState state() {
        var overview = TuiSnapshot.OverviewData.empty();
        var snapshot = new TuiSnapshot(
            overview,
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
        return new TuiState(snapshot);
    }

    @Test
    @DisplayName("nextTab wraps around from last tab (4) back to 0")
    void nextTab_wrapsAroundAtEnd() {
        var s = state();
        for (int i = 0; i < 4; i++) s.nextTab();
        assertThat(s.activeTab()).isEqualTo(4);
        s.nextTab();
        assertThat(s.activeTab()).isEqualTo(0);
    }

    @Test
    @DisplayName("prevTab wraps around from tab 0 back to last tab (4)")
    void prevTab_wrapsAroundAtStart() {
        var s = state();
        assertThat(s.activeTab()).isEqualTo(0);
        s.prevTab();
        assertThat(s.activeTab()).isEqualTo(4);
    }

    @Test
    @DisplayName("setTab clamps negative values to 0 and overflow to 4")
    void setTab_clampsToValidRange() {
        var s = state();
        s.setTab(-1);
        assertThat(s.activeTab()).isEqualTo(0);
        s.setTab(99);
        assertThat(s.activeTab()).isEqualTo(4);
    }

    @Test
    @DisplayName("focusFilter and blurFilter toggle filterFocused correctly")
    void filterFocused_togglesBetweenFocusAndBlur() {
        var s = state();
        s.nextTab(); // tab 1 (Profiles) has a filter
        assertThat(s.filterFocused()).isFalse();
        s.focusFilter();
        assertThat(s.filterFocused()).isTrue();
        s.blurFilter();
        assertThat(s.filterFocused()).isFalse();
    }

    @Test
    @DisplayName("currentFilter returns null for tab 0 (Overview)")
    void currentFilter_returnsNull_forOverview() {
        var s = state();
        // Tab 0 = Overview
        assertThat(s.currentFilter()).isNull();
    }

    @Test
    @DisplayName("currentFilter returns non-null FilterState for filterable tabs 1-4")
    void currentFilter_returnsNonNullForFilterableTabs() {
        var s = state();
        for (int tab = 1; tab <= 4; tab++) {
            s.setTab(tab);
            assertThat(s.currentFilter())
                .as("tab %d must have a non-null FilterState", tab)
                .isNotNull();
        }
    }

    @Test
    @DisplayName("selectedRow tracks cursor position correctly after navigation")
    void selectedRow_tracks_cursorPosition_after_navigation() {
        var s = state();
        s.nextTab(); // tab 1 (Profiles)
        assertThat(s.selectedRow()).isZero();

        s.nextRow(5);
        s.nextRow(5);
        assertThat(s.selectedRow())
            .as("selectedRow must reflect actual cursor position, not FilterState.selectedIndex")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("FilterState.isEmpty returns true when no query text has been typed")
    void filterState_isEmpty_trueWhenNoQuery() {
        var s = state();
        s.nextTab(); // tab 1 (Profiles)
        assertThat(s.currentFilter().isEmpty())
            .as("fresh FilterState must be empty — the fix uses isEmpty() to route to selectedRow()")
            .isTrue();
    }

    @Test
    @DisplayName("FilterState.selectedIndex starts at 0 regardless of cursor navigation")
    void filterState_selectedIndex_startsAt0_ignoringCursorNavigation() {
        var s = state();
        s.nextTab(); // tab 1 (Profiles)

        // Navigate cursor down with nextRow — this updates selectedRows[1] but NOT filterState.selectedIndex
        s.nextRow(5);
        s.nextRow(5);

        assertThat(s.currentFilter().selectedIndex())
            .as("filterState.selectedIndex stays 0 when no filter text is active — " +
                "confirms that using clampedIndex() on empty filter would always pick row 0 (the bug)")
            .isZero();
        assertThat(s.selectedRow())
            .as("selectedRow() correctly reflects the navigated cursor position")
            .isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // Plan-031 TDD — Task 2: Overview tab scroll via scrollOverview()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Overview tab scroll")
    class WhenScrollingOverview {

        @Test
        @DisplayName("scrollOverview(1) increments scroll offset from 0 to 1")
        void scrollOverview_positive_incrementsOffset() {
            var s = state();
            // Must be on tab 0 (Overview)
            assertThat(s.activeTab()).isZero();

            s.scrollOverview(1);

            assertThat(s.selectedRow())
                .as("selectedRow on tab 0 must reflect scroll offset after scrollOverview(1)")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("scrollOverview(-1) decrements scroll offset but not below 0")
        void scrollOverview_negative_decrementsButNotBelowZero() {
            var s = state();
            s.scrollOverview(3);

            s.scrollOverview(-1);

            assertThat(s.selectedRow())
                .as("scrollOverview(-1) must decrement offset")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("scrollOverview(-1) on offset 0 stays at 0")
        void scrollOverview_negativeAtZero_clampsToZero() {
            var s = state();

            s.scrollOverview(-1);

            assertThat(s.selectedRow())
                .as("scroll offset on Overview tab must not go below 0")
                .isZero();
        }

        @Test
        @DisplayName("scrollOverview increments uncapped — not gated on itemCount like nextRow")
        void scrollOverview_isUncapped_incrementsBeyondZero() {
            var s = state();
            // nextRow(0) does nothing because itemCount==0 gates it.
            // scrollOverview must increment regardless of itemCount.
            s.nextRow(0); // should be gated, stays 0

            s.scrollOverview(1); // must increment even when no items

            assertThat(s.selectedRow())
                .as("scrollOverview must not be gated by itemCount; nextRow(0) stays 0 but scrollOverview(1) must reach 1")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("scrollOverview clamps to the configured max offset (bottom)")
        void scrollOverview_clampsAtConfiguredMaxOffset() {
            var s = state();
            s.setOverviewMaxOffset(3);

            s.scrollOverview(10);

            assertThat(s.selectedRow())
                .as("scroll must not exceed the last-rendered max offset")
                .isEqualTo(3);
        }

        @Test
        @DisplayName("scrollOverview still clamps at 0 even with a max offset set")
        void scrollOverview_clampsAtZeroWithMaxSet() {
            var s = state();
            s.setOverviewMaxOffset(10);

            s.scrollOverview(-3);

            assertThat(s.selectedRow())
                .as("scroll must not go below 0 regardless of max offset")
                .isZero();
        }

        @Test
        @DisplayName("setOverviewMaxOffset re-clamps an offset stranded past the new max (resize)")
        void setOverviewMaxOffset_reclampsStaleOffset() {
            var s = state();
            s.scrollOverview(5); // default cap is unbounded before the first render
            assertThat(s.selectedRow()).isEqualTo(5);

            s.setOverviewMaxOffset(2);

            assertThat(s.selectedRow())
                .as("shrinking the viewport must pull a stranded offset back to the new max")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("setOverviewMaxOffset treats a negative max as 0")
        void setOverviewMaxOffset_negativeTreatedAsZero() {
            var s = state();
            s.scrollOverview(4);

            s.setOverviewMaxOffset(-5);

            assertThat(s.selectedRow())
                .as("a negative max offset clamps the offset to the top")
                .isZero();
        }

        @Test
        @DisplayName("scroll offset on tab 0 is independent of selectedRow on tab 1")
        void scrollOverview_offsetIsIndependentAcrossTabs() {
            var s = state();
            s.scrollOverview(5);
            assertThat(s.selectedRow()).as("tab 0 scroll offset").isEqualTo(5);

            s.nextTab(); // tab 1
            assertThat(s.selectedRow()).as("tab 1 must start at 0").isZero();

            s.nextRow(10);
            s.nextRow(10);
            assertThat(s.selectedRow()).as("tab 1 cursor").isEqualTo(2);

            s.setTab(0);
            assertThat(s.selectedRow())
                .as("returning to tab 0 must preserve its scroll offset")
                .isEqualTo(5);
        }
    }
}
