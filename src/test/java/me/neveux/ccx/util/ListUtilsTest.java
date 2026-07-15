package me.neveux.ccx.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("ListUtils.orderedUnion")
class ListUtilsTest {

    @Test
    @DisplayName("returns an empty mutable list when both inputs are null")
    void orderedUnion_when_bothNull_then_emptyMutableList() {
        var result = ListUtils.orderedUnion(null, null);

        assertThat(result).isEmpty();
        assertThatCode(() -> result.add("x")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("returns the distinct elements of the non-null list when first is null")
    void orderedUnion_when_firstNull_then_secondDistinct() {
        assertThat(ListUtils.orderedUnion(null, List.of("a", "b", "a")))
            .containsExactly("a", "b");
    }

    @Test
    @DisplayName("returns the distinct elements of the non-null list when second is null")
    void orderedUnion_when_secondNull_then_firstDistinct() {
        assertThat(ListUtils.orderedUnion(List.of("a", "a", "b"), null))
            .containsExactly("a", "b");
    }

    @Test
    @DisplayName("deduplicates across both lists preserving first-seen order")
    void orderedUnion_when_overlap_then_firstSeenOrder() {
        assertThat(ListUtils.orderedUnion(List.of("a", "b"), List.of("b", "c")))
            .containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("returns an empty list for two empty inputs")
    void orderedUnion_when_bothEmpty_then_empty() {
        assertThat(ListUtils.orderedUnion(List.of(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("returns a new list, not a reference to either input")
    void orderedUnion_when_called_then_newListReference() {
        var first = List.of("a");
        var second = List.of("b");

        var result = ListUtils.orderedUnion(first, second);

        assertThat(result).isNotSameAs(first).isNotSameAs(second);
    }
}
