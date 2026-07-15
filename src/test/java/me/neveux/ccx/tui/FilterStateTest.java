// Wave 2 — TDD baseline (Phase 3): FilterState does not exist yet.
// These tests MUST fail until FilterState is created in the tui package.
package me.neveux.ccx.tui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FilterState")
class FilterStateTest {

    private FilterState filterState;

    @BeforeEach
    void setUp() {
        filterState = new FilterState();
    }

    // -----------------------------------------------------------------------
    // filter() — pure substring match logic
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("filter()")
    class FilterMethod {

        @Test
        @DisplayName("returns all items when query is empty")
        void filter_emptyQuery_returnsAllItems() {
            var items = List.of("java", "quarkus", "kotlin");

            var result = filterState.filter(items, Function.identity());

            assertThat(result)
                .as("empty query must return all items unfiltered")
                .containsExactlyInAnyOrder("java", "quarkus", "kotlin");
        }

        @Test
        @DisplayName("case-insensitive substring match returns matching items")
        void filter_caseInsensitiveSubstring_matchesCorrectly() {
            filterState.appendChar('J');
            filterState.appendChar('A');
            filterState.appendChar('V');

            var items = List.of("java", "quarkus", "java-ai", "kotlin");

            var result = filterState.filter(items, Function.identity());

            assertThat(result)
                .as("case-insensitive filter must match 'java' and 'java-ai' for query 'JAV'")
                .containsExactlyInAnyOrder("java", "java-ai");
        }

        @Test
        @DisplayName("returns empty list when no item matches the query")
        void filter_noMatch_returnsEmptyList() {
            filterState.appendChar('x');
            filterState.appendChar('y');
            filterState.appendChar('z');

            var items = List.of("java", "quarkus", "kotlin");

            var result = filterState.filter(items, Function.identity());

            assertThat(result)
                .as("filter must return empty list when no item matches query 'xyz'")
                .isEmpty();
        }

        @Test
        @DisplayName("applies label function to extract searchable text")
        void filter_usesLabelFunction() {
            filterState.appendChar('j');

            // Items are records; label extracts a field
            record Item(String name, String tag) {}
            var items = List.of(
                new Item("alpha", "java"),
                new Item("beta", "quarkus"),
                new Item("gamma", "java-ai")
            );

            var result = filterState.filter(items, Item::tag);

            assertThat(result)
                .as("filter must use the label function to match against 'java' and 'java-ai'")
                .hasSize(2)
                .extracting(Item::tag)
                .containsExactlyInAnyOrder("java", "java-ai");
        }

        @Test
        @DisplayName("returns all items when query is cleared after being set")
        void filter_afterClear_returnsAllItems() {
            filterState.appendChar('j');
            filterState.clear();

            var items = List.of("java", "quarkus");

            var result = filterState.filter(items, Function.identity());

            assertThat(result)
                .as("filter must return all items after query is cleared")
                .containsExactlyInAnyOrder("java", "quarkus");
        }
    }

    // -----------------------------------------------------------------------
    // fuzzy matching — subsequence (fzf-style)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("fuzzy matching")
    class FuzzyMatching {

        @Test
        @DisplayName("non-contiguous characters match in order (subsequence)")
        void fuzzyMatch_nonContiguous() {
            filterState.appendChar('j');
            filterState.appendChar('q');

            var result = filterState.filter(List.of("java-quarkus", "quarkus", "java"), Function.identity());

            assertThat(result)
                .as("'jq' must match 'java-quarkus' via subsequence matching")
                .containsExactly("java-quarkus");
        }

        @Test
        @DisplayName("characters must appear in order — reversed query does not match")
        void fuzzyMatch_orderMatters() {
            filterState.appendChar('q');
            filterState.appendChar('j');

            var result = filterState.filter(List.of("java-quarkus"), Function.identity());

            assertThat(result)
                .as("'qj' must NOT match 'java-quarkus' since q appears after j in the text")
                .isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // clampedIndex()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("clampedIndex()")
    class ClampedIndex {

        @Test
        @DisplayName("returns selectedIndex when within list bounds")
        void clampedIndex_whenIndexWithinBounds_returnsSameIndex() {
            filterState.setSelectedIndex(1);
            var list = List.of("a", "b", "c");

            assertThat(filterState.clampedIndex(list))
                .as("clampedIndex must return selectedIndex when it is within bounds")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("clamps to last valid index when selectedIndex exceeds list size")
        void clampedIndex_whenIndexExceedsListSize_clampsToMax() {
            filterState.setSelectedIndex(10);
            var list = List.of("a", "b", "c");

            assertThat(filterState.clampedIndex(list))
                .as("clampedIndex must clamp to list.size()-1 when selectedIndex >= list.size()")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("returns 0 when list is empty")
        void clampedIndex_whenEmptyList_returnsZero() {
            filterState.setSelectedIndex(3);

            assertThat(filterState.clampedIndex(List.of()))
                .as("clampedIndex must return 0 when list is empty")
                .isZero();
        }

        @Test
        @DisplayName("returns 0 when selectedIndex is 0 and list is non-empty")
        void clampedIndex_atZeroIndex_returnsZero() {
            filterState.setSelectedIndex(0);

            assertThat(filterState.clampedIndex(List.of("only-item")))
                .as("clampedIndex must return 0 when selectedIndex is 0")
                .isZero();
        }
    }

    // -----------------------------------------------------------------------
    // appendChar() / backspace() / clear() / query()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("text mutation methods")
    class TextMutation {

        @Test
        @DisplayName("appendChar builds query string incrementally")
        void appendChar_buildsQuery() {
            filterState.appendChar('j');
            filterState.appendChar('a');
            filterState.appendChar('v');

            assertThat(filterState.query())
                .as("query must reflect all appended chars in order")
                .isEqualTo("jav");
        }

        @Test
        @DisplayName("backspace removes the last character from query")
        void backspace_removesLastChar() {
            filterState.appendChar('j');
            filterState.appendChar('a');
            filterState.appendChar('v');
            filterState.backspace();

            assertThat(filterState.query())
                .as("backspace must remove the last character, leaving 'ja'")
                .isEqualTo("ja");
        }

        @Test
        @DisplayName("backspace on empty query does not throw")
        void backspace_onEmptyQuery_isNoOp() {
            // Must not throw; query remains empty
            filterState.backspace();

            assertThat(filterState.query())
                .as("backspace on empty query must leave query empty")
                .isEmpty();
        }

        @Test
        @DisplayName("clear resets query to empty string")
        void clear_resetsQuery() {
            filterState.appendChar('j');
            filterState.appendChar('a');
            filterState.clear();

            assertThat(filterState.query())
                .as("clear must reset query to empty string")
                .isEmpty();
        }

        @Test
        @DisplayName("clear resets selectedIndex to 0")
        void clear_resetsSelectedIndex() {
            filterState.setSelectedIndex(5);
            filterState.appendChar('j');
            filterState.clear();

            assertThat(filterState.selectedIndex())
                .as("clear must reset selectedIndex to 0")
                .isZero();
        }

        @Test
        @DisplayName("isEmpty returns true when query is empty")
        void isEmpty_whenQueryIsEmpty_returnsTrue() {
            assertThat(filterState.isEmpty())
                .as("isEmpty must return true for a fresh FilterState")
                .isTrue();
        }

        @Test
        @DisplayName("isEmpty returns false when query has content")
        void isEmpty_whenQueryHasContent_returnsFalse() {
            filterState.appendChar('x');

            assertThat(filterState.isEmpty())
                .as("isEmpty must return false after appending a character")
                .isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // selectedIndex navigation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("selectedIndex management")
    class SelectedIndex {

        @Test
        @DisplayName("initial selectedIndex is 0")
        void selectedIndex_initiallyZero() {
            assertThat(filterState.selectedIndex())
                .as("initial selectedIndex must be 0")
                .isZero();
        }

        @Test
        @DisplayName("setSelectedIndex stores the given index")
        void setSelectedIndex_storesValue() {
            filterState.setSelectedIndex(3);

            assertThat(filterState.selectedIndex())
                .as("selectedIndex must equal the value set via setSelectedIndex")
                .isEqualTo(3);
        }
    }
}
