package me.neveux.ccx.tui;

import dev.tamboui.layout.Rect;
import dev.tamboui.markdown.MarkdownView;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import me.neveux.ccx.tui.CatppuccinTheme;

/**
 * Stateless utility that renders a Markdown string into a Tamboui preview pane area.
 * All tab implementations delegate here rather than building the {@link dev.tamboui.markdown.MarkdownView}
 * widget inline, which keeps consistent border and colour styling across tabs.
 */
public final class PreviewRenderer {
    public static void render(Frame frame, Rect area, String title, String markdownContent) {
        var widget = MarkdownView.builder()
            .source(markdownContent != null ? markdownContent : "")
            .block(Block.builder()
                .title(" " + title + " ")
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(CatppuccinTheme.SURFACE1))
                .build())
            .build();
        frame.renderWidget(widget, area);
    }
}
