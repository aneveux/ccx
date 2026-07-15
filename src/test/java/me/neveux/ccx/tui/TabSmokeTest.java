package me.neveux.ccx.tui;

import me.neveux.ccx.tui.tabs.ClaudeMdTab;
import me.neveux.ccx.tui.tabs.FilterableTab;
import me.neveux.ccx.tui.tabs.McpTab;
import me.neveux.ccx.tui.tabs.OverviewTab;
import me.neveux.ccx.tui.tabs.PluginsTab;
import me.neveux.ccx.tui.tabs.ProfilesTab;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Tab smoke tests — verify instantiation and basic structure")
class TabSmokeTest {

    /** Minimal snapshot with empty lists and valid defaults — no nulls anywhere. */
    private static TuiSnapshot minimalSnapshot() {
        var overview = TuiSnapshot.OverviewData.empty();
        return new TuiSnapshot(
            overview,
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    @Nested
    @DisplayName("OverviewTab")
    class OverviewTabTests {

        @Test
        @DisplayName("is the standalone overview tab (not a FilterableTab) and maps to the OVERVIEW enum")
        void overviewTab_isStandalone_andMapsToOverviewEnum() {
            var tab = new OverviewTab();

            assertThat(tab)
                .as("OverviewTab must be its own type, not a FilterableTab")
                .isExactlyInstanceOf(OverviewTab.class)
                .isNotInstanceOf(FilterableTab.class);
            assertThat(Tab.OVERVIEW.filterable())
                .as("the OVERVIEW enum entry must be non-filterable, matching OverviewTab having no filter bar")
                .isFalse();
            assertThat(Tab.OVERVIEW.title())
                .as("OVERVIEW enum title must label the tab as Overview")
                .contains("Overview");
        }
    }

    @Nested
    @DisplayName("ProfilesTab")
    class ProfilesTabTests {

        @Test
        @DisplayName("is a FilterableTab with its own ListState and maps to the filterable PROFILES enum")
        void profilesTab_isFilterable_withListState_andMapsToProfilesEnum() {
            var tab = new ProfilesTab();

            assertThat(tab)
                .as("ProfilesTab must extend FilterableTab")
                .isInstanceOf(FilterableTab.class);
            assertThat(tab.listState())
                .as("a freshly constructed FilterableTab must own a non-null ListState")
                .isNotNull();
            assertThat(Tab.PROFILES.filterable())
                .as("the PROFILES enum entry must be filterable, matching ProfilesTab")
                .isTrue();
            assertThat(Tab.PROFILES.title())
                .as("PROFILES enum title must label the tab as Profiles")
                .contains("Profiles");
        }
    }

    @Nested
    @DisplayName("PluginsTab")
    class PluginsTabTests {

        @Test
        @DisplayName("is a FilterableTab with its own ListState and maps to the filterable PLUGINS enum")
        void pluginsTab_isFilterable_withListState_andMapsToPluginsEnum() {
            var tab = new PluginsTab();

            assertThat(tab)
                .as("PluginsTab must extend FilterableTab")
                .isInstanceOf(FilterableTab.class);
            assertThat(tab.listState())
                .as("a freshly constructed FilterableTab must own a non-null ListState")
                .isNotNull();
            assertThat(Tab.PLUGINS.filterable())
                .as("the PLUGINS enum entry must be filterable, matching PluginsTab")
                .isTrue();
            assertThat(Tab.PLUGINS.title())
                .as("PLUGINS enum title must label the tab as Plugins")
                .contains("Plugins");
        }
    }

    @Nested
    @DisplayName("McpTab")
    class McpTabTests {

        @Test
        @DisplayName("is a FilterableTab with its own ListState and maps to the filterable MCP enum")
        void mcpTab_isFilterable_withListState_andMapsToMcpEnum() {
            var tab = new McpTab();

            assertThat(tab)
                .as("McpTab must extend FilterableTab")
                .isInstanceOf(FilterableTab.class);
            assertThat(tab.listState())
                .as("a freshly constructed FilterableTab must own a non-null ListState")
                .isNotNull();
            assertThat(Tab.MCP.filterable())
                .as("the MCP enum entry must be filterable, matching McpTab")
                .isTrue();
            assertThat(Tab.MCP.title())
                .as("MCP enum title must label the tab as MCPs")
                .contains("MCPs");
        }
    }

    @Nested
    @DisplayName("ClaudeMdTab")
    class ClaudeMdTabTests {

        @Test
        @DisplayName("is a FilterableTab with its own ListState and maps to the filterable CLAUDEMD enum")
        void claudeMdTab_isFilterable_withListState_andMapsToClaudeMdEnum() {
            var tab = new ClaudeMdTab();

            assertThat(tab)
                .as("ClaudeMdTab must extend FilterableTab")
                .isInstanceOf(FilterableTab.class);
            assertThat(tab.listState())
                .as("a freshly constructed FilterableTab must own a non-null ListState")
                .isNotNull();
            assertThat(Tab.CLAUDEMD.filterable())
                .as("the CLAUDEMD enum entry must be filterable, matching ClaudeMdTab")
                .isTrue();
            assertThat(Tab.CLAUDEMD.title())
                .as("CLAUDEMD enum title must label the tab as Claude.md")
                .contains("Claude.md");
        }
    }

    @Nested
    @DisplayName("TuiState with minimal snapshot")
    class TuiStateWithSnapshot {

        @Test
        @DisplayName("TuiState can be constructed with minimal snapshot without NPE")
        void tuiState_constructsWithMinimalSnapshot() {
            var snap = minimalSnapshot();

            var state = new TuiState(snap);

            assertThat(state).isNotNull();
            assertThat(state.snapshot()).isSameAs(snap);
            assertThat(state.activeTab()).isZero();
        }

        @Test
        @DisplayName("TuiState tab navigation stays within bounds on minimal snapshot")
        void tuiState_tabNavigation_staysInBounds() {
            var state = new TuiState(minimalSnapshot());

            state.nextTab();
            state.nextTab();
            state.prevTab();

            assertThat(state.activeTab())
                .as("activeTab must be exactly 1 after next→next→prev (0→1→2→1)")
                .isEqualTo(1);
        }
    }
}
