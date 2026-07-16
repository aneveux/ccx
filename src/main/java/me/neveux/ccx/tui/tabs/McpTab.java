package me.neveux.ccx.tui.tabs;

import dev.tamboui.text.Line;
import me.neveux.ccx.tui.Quips;
import me.neveux.ccx.tui.TuiSnapshot;

import java.util.List;
import java.util.function.Function;

/**
 * TUI tab for MCP server configuration (tab 4). Lists all MCP servers from the project
 * {@code .claude/settings.json}, labelled with their transport type (SSE or stdio).
 * The preview pane shows connection details for the selected server.
 */
public final class McpTab extends FilterableTab<TuiSnapshot.McpEntry> {

    @Override protected String tabTitle() { return "MCPs"; }
    @Override protected String previewTitle() { return "MCP Details"; }

    @Override protected List<TuiSnapshot.McpEntry> items(TuiSnapshot snap) { return snap.mcps(); }

    @Override protected Function<TuiSnapshot.McpEntry, String> filterKey() { return TuiSnapshot.McpEntry::name; }

    @Override protected Line listLabelLine(TuiSnapshot.McpEntry e, TuiSnapshot snap) {
        var type = e.config().type() != null ? e.config().type() : "?";
        // MCP transport type is not a status — use transport-flavoured emoji, not the status palette.
        var typeIcon = switch (type) {
            case "sse"   -> "🌐";
            case "stdio" -> "🔌";
            default      -> "🔧";
        };
        return Line.from(typeIcon + " " + e.name() + "  [" + type + "]");
    }

    @Override protected String preview(TuiSnapshot.McpEntry e, TuiSnapshot snap) {
        var cfg = e.config();
        return "## " + e.name() + "\n"
            + "**Type:** " + (cfg.type() != null ? cfg.type() : "n/a") + "\n"
            + "**URL:** " + (cfg.url() != null ? cfg.url() : "n/a") + "\n"
            + "**Command:** " + (cfg.command() != null ? cfg.command() : "n/a");
    }

    @Override
    protected String emptyStateMessage(TuiSnapshot snap) {
        if (snap.mcps().isEmpty()) {
            return "# MCP Servers\n\n"
                + "_No MCP servers configured._\n\n"
                + "To add one, edit `.claude/settings.json`:\n"
                + "```json\n"
                + "\"mcpServers\": {\n"
                + "  \"my-server\": {\n"
                + "    \"type\": \"sse\",\n"
                + "    \"url\": \"http://localhost:3000\"\n"
                + "  }\n"
                + "}\n"
                + "```";
        }
        return Quips.EMPTY[0] + " nothing matches";
    }
}
