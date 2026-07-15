package me.neveux.ccx.tui.tabs;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.input.TextInput;
import dev.tamboui.widgets.list.ListItem;
import dev.tamboui.widgets.list.ListState;
import dev.tamboui.widgets.list.ListWidget;
import me.neveux.ccx.tui.CatppuccinTheme;
import me.neveux.ccx.tui.FilterState;
import me.neveux.ccx.tui.PreviewRenderer;
import me.neveux.ccx.tui.TuiSnapshot;

import java.util.List;
import java.util.function.Function;

/**
 * Abstract base for all list+preview TUI tabs. Manages the filter bar, the
 * {@link ListState} for scroll/selection, and the split layout (filter row /
 * list panel / preview panel). Subclasses supply the data slice, the label
 * renderer, and the preview Markdown — everything else is handled here.
 *
 * @param <T> the item type shown in this tab's list
 */
public abstract class FilterableTab<T> {

    private final ListState listState = new ListState();

    public ListState listState() { return listState; }

    protected abstract String tabTitle();
    protected String previewTitle() { return tabTitle() + " details"; }
    protected abstract List<T> items(TuiSnapshot snap);
    protected abstract Function<T, String> filterKey();
    protected abstract Line listLabelLine(T item, TuiSnapshot snap);
    protected abstract String preview(T item, TuiSnapshot snap);
    protected String emptyStateMessage(TuiSnapshot snap) { return "(⌒_⌒;) nothing to show"; }

    public void render(Frame frame, Rect area, TuiSnapshot snap, FilterState filterState, int selectedRow) {
        var vertAreas = Layout.vertical()
            .constraints(Constraint.length(3), Constraint.fill())
            .split(area);
        var filterRow = vertAreas.get(0);
        var body = vertAreas.get(1);

        var filterBorderColor = filterState.focused() ? CatppuccinTheme.SAPPHIRE : CatppuccinTheme.SURFACE1;
        var filterInput = TextInput.builder()
            .block(Block.builder()
                .title(" filter (press /) ")
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(filterBorderColor))
                .build())
            .build();
        frame.renderStatefulWidget(filterInput, filterRow, filterState.inputState());

        var horzAreas = Layout.horizontal()
            .constraints(Constraint.percentage(40), Constraint.fill())
            .split(body);
        var listArea = horzAreas.get(0);
        var previewArea = horzAreas.get(1);

        var filtered = filterState.filter(items(snap), filterKey());
        var listItems = filtered.stream()
            .map(item -> ListItem.from(listLabelLine(item, snap)).toSizedWidget())
            .toList();

        var listWidget = ListWidget.builder()
            .items(listItems)
            .highlightStyle(Style.EMPTY.bg(CatppuccinTheme.SURFACE1).fg(CatppuccinTheme.TEXT).bold())
            .highlightSymbol("> ")
            .block(Block.builder()
                .title(" " + tabTitle() + " ")
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(CatppuccinTheme.MAUVE))
                .build())
            .build();

        var displayIdx = filterState.isEmpty()
            ? Math.min(selectedRow, Math.max(0, filtered.size() - 1))
            : filterState.clampedIndex(filtered);
        listState.select(displayIdx);
        frame.renderStatefulWidget(listWidget, listArea, listState);

        if (filtered.isEmpty()) {
            PreviewRenderer.render(frame, previewArea, previewTitle(), emptyStateMessage(snap));
        } else {
            PreviewRenderer.render(frame, previewArea, previewTitle(), preview(filtered.get(displayIdx), snap));
        }
    }
}
