package me.neveux.ccx.tui.tabs;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.markdown.MarkdownView;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import me.neveux.ccx.model.ProfileDriftState;
import me.neveux.ccx.tui.CatppuccinTheme;
import me.neveux.ccx.tui.Glyphs;
import me.neveux.ccx.tui.Quips;
import me.neveux.ccx.tui.TuiSnapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Overview tab — vertically-stacked, full-width bordered tamboui {@link Block} sections.
 * The whole stack is rendered once into a tall scratch {@link Buffer}, then the visible
 * window is composited into the frame by a viewport-clipped per-cell copy bounded to the
 * body {@code area}, giving unified page scroll without bleeding into adjacent regions.
 */
public final class OverviewTab {

    /** One full-width stacked section: a titled body plus its computed display height. */
    private record Section(String title, int height, SectionRenderer renderer) {}

    @FunctionalInterface
    private interface SectionRenderer {
        void render(Buffer scratch, Rect area);
    }

    /**
     * Renders the stacked overview into {@code frame} and returns the maximum scroll offset
     * for the rendered content given the viewport, so the caller can clamp scrolling.
     */
    public int render(Frame frame, Rect area, TuiSnapshot snap, Path projectDir, int scrollOffset) {
        var ov = snap.overview();
        var sections = buildSections(ov, projectDir, area.width());

        var totalHeight = sections.stream().mapToInt(Section::height).sum();
        if (totalHeight < area.height()) totalHeight = area.height();

        var scratchArea = new Rect(0, 0, area.width(), totalHeight);
        var scratch = Buffer.empty(scratchArea);

        var constraints = sections.stream()
            .map(s -> Constraint.length(s.height()))
            .toArray(Constraint[]::new);
        var slots = Layout.vertical().constraints(constraints).split(scratchArea);
        for (int i = 0; i < sections.size(); i++) {
            sections.get(i).renderer().render(scratch, slots.get(i));
        }

        var maxOffset = Math.max(0, totalHeight - area.height());
        var clampedOffset = Math.max(0, Math.min(scrollOffset, maxOffset));

        for (int row = 0; row < area.height(); row++) {
            int srcY = row + clampedOffset;
            if (srcY < 0 || srcY >= totalHeight) continue;
            for (int x = 0; x < area.width(); x++) {
                frame.buffer().set(area.left() + x, area.top() + row, scratch.get(x, srcY));
            }
        }
        return maxOffset;
    }

    private List<Section> buildSections(TuiSnapshot.OverviewData ov, Path projectDir, int width) {
        var sections = new ArrayList<Section>();

        // 1. Context
        var contextBody = contextBody(ov, projectDir);
        sections.add(new Section("Context", bodyHeight(contextBody),
            (scratch, area) -> renderBlock(scratch, area, " Context ", contextBody)));

        // 2. Profiles
        var profilesBody = profilesBody(ov);
        sections.add(new Section("Profiles", bodyHeight(profilesBody),
            (scratch, area) -> renderBlock(scratch, area, " Profiles ", profilesBody)));

        // 3. Plugins (Project | Global side-by-side sub-blocks)
        var projectPlugins = ov.projectPluginNames();
        var globalPlugins = ov.globalPluginNames();
        var pluginsTitle = " Plugins  (" + ov.projectPluginCount() + " project · "
            + ov.globalPluginCount() + " global) ";
        int pluginRows = Math.max(1, Math.max(projectPlugins.size(), globalPlugins.size()));
        int pluginsHeight = pluginRows + 4; // inner sub-block border (2) + outer border (2)
        sections.add(new Section("Plugins", pluginsHeight,
            (scratch, area) -> renderSplitBlock(scratch, area, pluginsTitle,
                List.of(
                    new SubBlock(" Project ", listBody(projectPlugins)),
                    new SubBlock(" Global ", listBody(globalPlugins))))));

        // 4. Config row 1 — CLAUDE.md | Fragments | Rules Files
        var claudeMdBody = claudeMdBody(ov);
        var fragmentsBody = listBody(ov.fragmentNames());
        var rulesBody = listBody(ov.rulesFileNames());
        int row1Body = Math.max(linesOf(claudeMdBody),
            Math.max(linesOf(fragmentsBody), linesOf(rulesBody)));
        sections.add(new Section("Config1", row1Body + 4,
            (scratch, area) -> renderSplitBlock(scratch, area, null,
                List.of(
                    new SubBlock(" CLAUDE.md ", claudeMdBody),
                    new SubBlock(" Fragments ", fragmentsBody),
                    new SubBlock(" Rules Files ", rulesBody)))));

        // 5. Config row 2 — MCP Servers | Hooks | .claudeignore
        var mcpBody = mcpBody(ov);
        var hooksBody = hooksBody(ov);
        var ignoreBody = ignoreBody(ov);
        int row2Body = Math.max(linesOf(mcpBody),
            Math.max(linesOf(hooksBody), linesOf(ignoreBody)));
        sections.add(new Section("Config2", row2Body + 4,
            (scratch, area) -> renderSplitBlock(scratch, area, null,
                List.of(
                    new SubBlock(" MCP Servers ", mcpBody),
                    new SubBlock(" Hooks ", hooksBody),
                    new SubBlock(" .claudeignore ", ignoreBody)))));

        return sections;
    }

    private record SubBlock(String title, String body) {}

    private String contextBody(TuiSnapshot.OverviewData ov, Path projectDir) {
        if (ov.contextFilePath() == null) {
            return Glyphs.BROKEN + "  ccx not activated in this project\n" + Quips.EMPTY[0];
        }
        var relative = relativize(projectDir, ov.contextFilePath());
        int profiles = ov.enabledProfileNames().size();
        int plugins = ov.projectPluginCount() + ov.globalPluginCount();
        int hooks = ov.hookCount() + ov.globalHookCount();
        var summary = profiles + " profiles · " + plugins + " plugins · "
            + hooks + " hooks · " + ov.mcpCount() + " mcp";
        return Glyphs.ENABLED + "  `" + relative + "` · (" + summary + ")";
    }

    private String relativize(Path projectDir, String contextFilePath) {
        try {
            return projectDir.relativize(Path.of(contextFilePath)).toString();
        } catch (IllegalArgumentException e) {
            return contextFilePath;
        }
    }

    private String profilesBody(TuiSnapshot.OverviewData ov) {
        if (ov.enabledProfileNames().isEmpty()) return Quips.EMPTY[0];
        var sb = new StringBuilder();
        for (var name : ov.enabledProfileNames()) {
            var drift = ov.profileDriftStates().get(name);
            String icon;
            if (drift == ProfileDriftState.ALTERED) {
                icon = Glyphs.DRIFTED;
            } else if (drift == ProfileDriftState.BROKEN) {
                icon = Glyphs.BROKEN;
            } else {
                icon = Glyphs.ENABLED;
            }
            sb.append("- ").append(icon).append(' ').append(name).append('\n');
        }
        return sb.toString();
    }

    private String claudeMdBody(TuiSnapshot.OverviewData ov) {
        var sb = new StringBuilder();
        sb.append("- ").append(ov.globalClaudeMdPath() != null ? Glyphs.ENABLED : Glyphs.AVAILABLE)
          .append(" global\n");
        sb.append("- ").append(ov.projectClaudeMdPath() != null ? Glyphs.ENABLED : Glyphs.AVAILABLE)
          .append(" project\n");
        sb.append("- ").append(ov.privateClaudeMdPath() != null ? Glyphs.ENABLED : Glyphs.AVAILABLE)
          .append(" private\n");
        return sb.toString();
    }

    private String mcpBody(TuiSnapshot.OverviewData ov) {
        var names = ov.mcpServerNames();
        if (names.isEmpty()) return Quips.EMPTY[0];
        if (names.size() > 6) return ov.mcpCount() + " servers";
        return listBody(names);
    }

    private String hooksBody(TuiSnapshot.OverviewData ov) {
        if (ov.hookCount() == 0 && ov.globalHookCount() == 0) return Quips.EMPTY[0];
        return "- project " + ov.hookCount() + "\n- global " + ov.globalHookCount() + "\n";
    }

    private String ignoreBody(TuiSnapshot.OverviewData ov) {
        var ci = ov.claudeIgnore();
        var sb = new StringBuilder();
        sb.append("- ").append(ci.presentProject() ? Glyphs.ENABLED : Glyphs.AVAILABLE)
          .append(" project (").append(ci.projectEntryCount()).append(")\n");
        sb.append("- ").append(ci.presentGlobal() ? Glyphs.ENABLED : Glyphs.AVAILABLE)
          .append(" global (").append(ci.globalEntryCount()).append(")\n");
        return sb.toString();
    }

    private String listBody(List<String> names) {
        if (names.isEmpty()) return Quips.EMPTY[0];
        var sb = new StringBuilder();
        for (var name : names) sb.append("- ").append(name).append('\n');
        return sb.toString();
    }

    private int linesOf(String body) {
        return (int) (body.chars().filter(c -> c == '\n').count() + 1);
    }

    private int bodyHeight(String body) {
        return linesOf(body) + 2; // rounded border top + bottom
    }

    private void renderBlock(Buffer scratch, Rect area, String title, String body) {
        if (area.width() <= 0 || area.height() <= 0) return;
        markdownWidget(title, body).render(area, scratch);
    }

    private void renderSplitBlock(Buffer scratch, Rect area, String outerTitle, List<SubBlock> subs) {
        if (area.width() <= 0 || area.height() <= 0) return;

        Rect inner = area;
        if (outerTitle != null) {
            blockOnly(outerTitle).render(area, scratch);
            inner = new Rect(area.left() + 1, area.top() + 1,
                Math.max(0, area.width() - 2), Math.max(0, area.height() - 2));
            if (inner.width() <= 0 || inner.height() <= 0) return;
        }

        var constraints = new Constraint[subs.size()];
        for (int i = 0; i < subs.size(); i++) constraints[i] = Constraint.fill(1);
        var cells = Layout.horizontal().constraints(constraints).split(inner);
        for (int i = 0; i < subs.size(); i++) {
            var cell = cells.get(i);
            if (cell.width() <= 0 || cell.height() <= 0) continue;
            markdownWidget(subs.get(i).title(), subs.get(i).body()).render(cell, scratch);
        }
    }

    private static MarkdownView markdownWidget(String title, String source) {
        return MarkdownView.builder()
            .source(source)
            .block(blockOnly(title))
            .build();
    }

    private static Block blockOnly(String title) {
        return Block.builder()
            .title(title)
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderStyle(Style.EMPTY.fg(CatppuccinTheme.SURFACE1))
            .build();
    }
}
