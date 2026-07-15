package me.neveux.ccx.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TuiSnapshot.OverviewData — fragmentNames / mcpServerNames (plan 038 Overview redesign)")
class TuiSnapshotOverviewDataTest {

    @Nested
    @DisplayName("new list fields")
    class NewListFields {

        @Test
        @DisplayName("builder accepts fragmentNames and the record exposes them")
        void builder_setsAndExposesFragmentNames() {
            var data = new TuiSnapshot.OverviewData.Builder()
                .fragmentNames(List.of("java.md", "go.md"))
                .build();

            assertThat(data.fragmentNames())
                .as("fragmentNames() must return what the builder was given")
                .containsExactly("java.md", "go.md");
        }

        @Test
        @DisplayName("builder accepts mcpServerNames and the record exposes them")
        void builder_setsAndExposesMcpServerNames() {
            var data = new TuiSnapshot.OverviewData.Builder()
                .mcpServerNames(List.of("playwright", "github"))
                .build();

            assertThat(data.mcpServerNames())
                .as("mcpServerNames() must return what the builder was given")
                .containsExactly("playwright", "github");
        }
    }

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("empty() yields non-null empty lists for both new fields")
        void empty_yieldsEmptyLists() {
            var data = TuiSnapshot.OverviewData.empty();

            assertThat(data.fragmentNames())
                .as("fragmentNames must default to a non-null empty list")
                .isNotNull()
                .isEmpty();
            assertThat(data.mcpServerNames())
                .as("mcpServerNames must default to a non-null empty list")
                .isNotNull()
                .isEmpty();
        }

        @Test
        @DisplayName("default builder yields non-null empty lists for both new fields")
        void defaultBuilder_yieldsEmptyLists() {
            var data = new TuiSnapshot.OverviewData.Builder().build();

            assertThat(data.fragmentNames()).isNotNull().isEmpty();
            assertThat(data.mcpServerNames()).isNotNull().isEmpty();
        }
    }
}
