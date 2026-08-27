package me.neveux.ccx.tui.tabs;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import me.neveux.ccx.model.McpServer;
import me.neveux.ccx.tui.FilterState;
import me.neveux.ccx.tui.TuiSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("McpTab")
class McpTabTest {

    private static TuiSnapshot snapshotWithMcpServers() {
        var sseServer = new McpServer("sse", "https://mcp.example.com/sse", null, null);
        var stdioServer = new McpServer("stdio", null, "python", List.of("-m", "mcp_server"));

        var mcpEntries = List.of(
            new TuiSnapshot.McpEntry("myserver", sseServer),
            new TuiSnapshot.McpEntry("otherserver", stdioServer)
        );

        return new TuiSnapshot(
            TuiSnapshot.OverviewData.empty(),
            List.of(),
            List.of(),
            mcpEntries,
            List.of()
        );
    }

    private static String render(TuiSnapshot snap, int width, int height) {
        var area = new Rect(0, 0, width, height);
        var buffer = Buffer.empty(area);
        var frame = Frame.forTesting(buffer);
        var filterState = new FilterState();
        new McpTab().render(frame, area, snap, filterState, 0);
        return buffer.toAnsiString();
    }

    @Nested
    @DisplayName("when two MCP servers are configured")
    class WhenTwoServers {

        @Test
        @DisplayName("renders both server names in the output")
        void render_showsBothServerNames() {
            var out = render(snapshotWithMcpServers(), 120, 30);

            assertThat(out)
                .as("McpTab must render the first server name 'myserver'")
                .contains("myserver");
            assertThat(out)
                .as("McpTab must render the second server name 'otherserver'")
                .contains("otherserver");
        }

        @Test
        @DisplayName("renders the SSE transport icon for an sse-type server")
        void render_showsSseIcon_forSseServer() {
            var out = render(snapshotWithMcpServers(), 120, 30);

            assertThat(out)
                .as("McpTab must render the SSE transport icon 🌐 for a server with type=sse")
                .contains("🌐");
        }

        @Test
        @DisplayName("renders the stdio transport icon for a stdio-type server")
        void render_showsStdioIcon_forStdioServer() {
            var out = render(snapshotWithMcpServers(), 120, 30);

            assertThat(out)
                .as("McpTab must render the stdio transport icon 🔌 for a server with type=stdio")
                .contains("🔌");
        }
    }

    @Nested
    @DisplayName("when no MCP servers are configured")
    class WhenEmpty {

        @Test
        @DisplayName("renders empty-state message without crashing")
        void render_emptyState_doesNotThrow() {
            var empty = new TuiSnapshot(
                TuiSnapshot.OverviewData.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
            );

            var out = render(empty, 120, 30);

            assertThat(out)
                .as("McpTab must render some non-blank output even with no servers")
                .isNotBlank();
        }
    }
}
