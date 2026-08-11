package me.neveux.ccx.tui.tabs;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import me.neveux.ccx.model.ProfileDriftState;
import me.neveux.ccx.tui.TuiSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Plan 038 — OverviewTab vertically-stacked block redesign")
class OverviewTabTest {

    private static final Path PROJECT_DIR = Path.of("/tmp/ccx-overview-test");

    private static TuiSnapshot.OverviewData populated() {
        return new TuiSnapshot.OverviewData.Builder()
            .contextFilePath(".claude/ccx-context.md")
            .enabledProfileNames(List.of("java-quarkus"))
            .profileDriftStates(Map.of("java-quarkus", ProfileDriftState.ENABLED))
            .projectPluginCount(1)
            .globalPluginCount(1)
            .projectPluginNames(List.of("java@claude-java-stack"))
            .globalPluginNames(List.of("globaltool@mkt"))
            .rulesFileNames(List.of("java.md"))
            .fragmentNames(List.of("java-fragment.md", "quarkus-fragment.md"))
            .mcpCount(2)
            .mcpServerNames(List.of("playwright-mcp", "github-mcp"))
            .projectClaudeMdPath("./CLAUDE.md")
            .globalClaudeMdPath("~/.claude/CLAUDE.md")
            .privateClaudeMdPath("./.claude/CLAUDE.md")
            .hookCount(1)
            .globalHookCount(0)
            .build();
    }

    private static TuiSnapshot snapshotOf(TuiSnapshot.OverviewData ov) {
        return new TuiSnapshot(ov, List.of(), List.of(), List.of(), List.of());
    }

    /** Render the new OverviewTab into a headless tamboui Buffer and return its ANSI string. */
    private static String render(TuiSnapshot.OverviewData ov, int width, int height) {
        var area = new Rect(0, 0, width, height);
        var buffer = Buffer.empty(area);
        var frame = Frame.forTesting(buffer);
        new OverviewTab().render(frame, area, snapshotOf(ov), PROJECT_DIR, 0);
        return buffer.toAnsiString();
    }

    @Nested
    @DisplayName("Context block")
    class ContextBlock {

        @Test
        @DisplayName("renders the context path and a profile/plugin/hook/mcp summary when activated")
        void activated_rendersPathAndSummary() {
            var out = render(populated(), 100, 40);

            assertThat(out)
                .as("the context file path must be visible in the Context block")
                .contains("ccx-context.md");
            assertThat(out)
                .as("a summary line must surface the profile / plugin / hook / mcp counts")
                .contains("profile")
                .contains("plugin");
        }

        @Test
        @DisplayName("renders the not-activated wording plus an EMPTY kaomoji when contextFilePath is null")
        void notActivated_rendersEmptyState() {
            var ov = new TuiSnapshot.OverviewData.Builder()
                .contextFilePath(null)
                .build();

            var out = render(ov, 100, 40);

            assertThat(out.toLowerCase())
                .as("absent context must show a 'not activated' style hint")
                .containsAnyOf("not activated", "not active", "absent");
            assertThat(out)
                .as("absent context must surface one of the EMPTY kaomoji")
                .containsAnyOf(me.neveux.ccx.tui.Quips.EMPTY);
        }
    }

    @Nested
    @DisplayName("fragments and MCP servers")
    class FragmentsAndMcp {

        @Test
        @DisplayName("renders fragment names when populated")
        void rendersFragmentNames() {
            var out = render(populated(), 100, 40);

            assertThat(out)
                .as("fragment names must appear somewhere in the rendered output")
                .contains("java-fragment.md");
        }

        @Test
        @DisplayName("renders MCP server names when populated")
        void rendersMcpServerNames() {
            var out = render(populated(), 100, 40);

            assertThat(out)
                .as("MCP server names must appear somewhere in the rendered output")
                .contains("playwright-mcp");
        }
    }

    @Nested
    @DisplayName("scroll composite stays inside the body area")
    class ScrollClipping {

        @Test
        @DisplayName("scrolled Overview never overwrites the row above the body (header bleed regression)")
        void scrolled_doesNotOverwriteHeaderRow() {
            int width = 100;
            int frameHeight = 12;
            var fullArea = new Rect(0, 0, width, frameHeight);
            var buffer = Buffer.empty(fullArea);
            var frame = Frame.forTesting(buffer);

            // Real layout: header row at y=0, body starts at y=1. The body is deliberately
            // shorter than the stacked content so maxOffset > 0 and scrolling is forced.
            var bodyArea = new Rect(0, 1, width, frameHeight - 1);

            int maxOffset = new OverviewTab()
                .render(frame, bodyArea, snapshotOf(populated()), PROJECT_DIR, 1000);

            assertThat(maxOffset)
                .as("content must be tall enough that scrolling is possible")
                .isGreaterThan(0);

            for (int x = 0; x < width; x++) {
                assertThat(buffer.get(x, 0).symbol())
                    .as("row 0 (above the body) must stay blank when the Overview is scrolled")
                    .isEqualTo(" ");
            }
        }
    }

    @Nested
    @DisplayName("section ordering (stacked vertically)")
    class SectionOrdering {

        @Test
        @DisplayName("Context precedes Profiles precedes Plugins precedes the config sections")
        void sectionsAreStackedInOrder() {
            var out = render(populated(), 100, 60);

            int context  = out.indexOf("Context");
            int profiles = out.indexOf("Profiles");
            int plugins  = out.indexOf("Plugins");
            int claudeMd = out.indexOf("CLAUDE.md");

            assertThat(context).as("Context heading must render").isGreaterThanOrEqualTo(0);
            assertThat(profiles).as("Profiles heading must render").isGreaterThan(context);
            assertThat(plugins).as("Plugins must come after Profiles").isGreaterThan(profiles);
            assertThat(claudeMd).as("config sections must come after Plugins").isGreaterThan(plugins);
        }
    }

    // -----------------------------------------------------------------------
    // Plan 039 — Context separator + parenthesised counts
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Plan 039 — Context block separator and parenthesised counts")
    class ContextSeparatorAndCounts {

        @Test
        @DisplayName("Context block renders path and parenthesised counts on the SAME row (path · ( … ))")
        void contextBlock_rendersPathAndParenWrappedCountsOnSameRow() {
            var out = render(populated(), 100, 40);

            // The counts summary substrings must still be present (Done When guard).
            assertThat(out)
                .as("the counts summary must still surface the profile / plugin substrings")
                .contains("profile")
                .contains("plugin");

            // The real fix: path, a '·' separator, and '(' wrapped counts collapse onto a
            // SINGLE markdown line — so ONE rendered row carries the filename, a '·', and '('.
            // Today the path is on its own row and the counts have no parens → no such row.
            var contextRow = out.lines()
                .filter(line -> line.contains("ccx-context.md"))
                .toList();

            assertThat(contextRow)
                .as("the Context block must render the context filename")
                .isNotEmpty();

            assertThat(contextRow)
                .as("the row carrying the context path must also carry the '·' separator "
                    + "and the '(' that opens the parenthesised counts")
                .anySatisfy(line -> assertThat(line)
                    .contains("·")
                    .contains("("));
        }
    }

    // -----------------------------------------------------------------------
    // Plan 039 — plugin names render one-per-line (bullet list, not space-jammed)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Plan 039 — plugin lists render one name per line")
    class PluginListsOnePerLine {

        @Test
        @DisplayName("two plugins in the SAME column each render on their own line (image #3: no space-jamming within a panel)")
        void pluginNames_eachOnOwnLine() {
            // Two SHORT project-column plugins that both fit the column width without wrapping.
            // The Project | Global split stays side-by-side; the fix is that names WITHIN a
            // panel become a bullet list (one per line) instead of a space-jammed softbreak collapse.
            var ov = new TuiSnapshot.OverviewData.Builder()
                .contextFilePath(".claude/ccx-context.md")
                .projectPluginCount(2)
                .projectPluginNames(List.of("cli@m", "core@m"))
                .globalPluginCount(0)
                .build();
            var out = render(ov, 100, 40);

            var first = "cli@m";
            var second = "core@m";

            assertThat(out)
                .as("both same-column plugin names must render somewhere")
                .contains(first)
                .contains(second);

            var offending = out.lines()
                .filter(line -> line.contains(first) && line.contains(second))
                .toList();

            assertThat(offending)
                .as("no single rendered line may contain both same-column plugin names — "
                    + "space-jammed softbreak collapse would put them on one row")
                .isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Plan 039 — Fragments block lists profile-provided fragment names
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Plan 039 — Fragments block lists fragment names")
    class FragmentsBlockListsNames {

        @Test
        @DisplayName("a profile-provided fragment name appears in the rendered Fragments block")
        void fragmentNames_appearInRenderedOutput() {
            var out = render(populated(), 100, 40);

            assertThat(out)
                .as("the first fixture fragment name must render in the Fragments block")
                .contains("java-fragment.md");
        }
    }

    @Nested
    @DisplayName("no two-column responsive mechanism")
    class NoTwoColumns {

        @Test
        @DisplayName("renders the same stacked blocks at width 80 and width 120")
        void stacksRegardlessOfWidth() {
            var narrow = render(populated(), 80, 60);
            var wide = render(populated(), 120, 60);

            for (var out : List.of(narrow, wide)) {
                assertThat(out)
                    .as("every section must render at any width — no two-column gating")
                    .contains("Context")
                    .contains("Profiles")
                    .contains("Plugins");
            }
        }
    }

    // -----------------------------------------------------------------------
    // Plan 046 — Config row 2 drops .claudeignore sub-block
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Plan 046 — Config row 2 has no .claudeignore sub-block")
    class ConfigRow2NoDotClaudeIgnore {

        @Test
        @DisplayName("rendered overview must not contain a .claudeignore section title")
        void configRow2_doesNotRenderClaudeIgnoreSubBlock() {
            var out = render(populated(), 100, 60);

            assertThat(out)
                .as("after migration to deny rules the .claudeignore sub-block must be gone "
                    + "— its title must not appear anywhere in the rendered output")
                .doesNotContain(".claudeignore");
        }

        @Test
        @DisplayName("Config row 2 renders MCP Servers and Hooks sub-blocks")
        void configRow2_rendersMcpAndHooksSubBlocks() {
            var out = render(populated(), 100, 60);

            assertThat(out)
                .as("MCP Servers sub-block must still render in Config row 2")
                .contains("MCP");
            assertThat(out)
                .as("Hooks sub-block must still render in Config row 2")
                .contains("Hooks");
        }
    }
}
