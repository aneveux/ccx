package me.neveux.ccx.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 3 — list commands print to stdout (kill the blank-output bug).
 *
 * <p>The blank-output bug: {@code InlineRenderer.renderList}/{@code renderConfirmation}
 * launched an interactive Tamboui sub-app on a TTY, so the plain rows never reached the
 * command's {@code PrintWriter} in a real terminal. {@code ccx ... list} is a query, not an
 * interactive surface (the interactive surface is {@code ccx tui}). The fix removes the
 * interactive branch entirely so output is always printed.
 *
 * <p>This is a structural regression guard: because the TTY branch cannot be exercised in a
 * non-TTY test run, the meaningful, breakable invariant is that the interactive renderer
 * classes no longer exist — the CLI list/confirmation paths therefore cannot route through
 * them. {@code DisplayRow} has since been inlined at each call site, so it must NOT exist.
 */
@DisplayName("Wave 3 — plain list output (no interactive renderer)")
class PlainListOutputTest {

    @Nested
    @DisplayName("interactive renderer removal")
    class RendererRemoval {

        @Test
        @DisplayName("InlineRenderer is removed so list/confirmation cannot branch into a sub-app")
        void inlineRenderer_isRemoved() {
            assertThatThrownBy(() -> Class.forName("me.neveux.ccx.tui.inline.InlineRenderer"))
                .as("InlineRenderer must be deleted — list commands print directly to the PrintWriter")
                .isInstanceOf(ClassNotFoundException.class);
        }

        @Test
        @DisplayName("ListInlineApp interactive sub-app is removed")
        void listInlineApp_isRemoved() {
            assertThatThrownBy(() -> Class.forName("me.neveux.ccx.tui.inline.ListInlineApp"))
                .as("ListInlineApp must be deleted — the interactive list sub-app is gone")
                .isInstanceOf(ClassNotFoundException.class);
        }

        @Test
        @DisplayName("ConfirmationInlineApp interactive sub-app is removed")
        void confirmationInlineApp_isRemoved() {
            assertThatThrownBy(() -> Class.forName("me.neveux.ccx.tui.inline.ConfirmationInlineApp"))
                .as("ConfirmationInlineApp must be deleted — the interactive confirmation sub-app is gone")
                .isInstanceOf(ClassNotFoundException.class);
        }

        @Test
        @DisplayName("DisplayRow is removed — list commands build output strings directly")
        void displayRow_isRemoved() {
            assertThatThrownBy(() -> Class.forName("me.neveux.ccx.tui.inline.DisplayRow"))
                .as("DisplayRow must be deleted — list commands print directly without an intermediate row model")
                .isInstanceOf(ClassNotFoundException.class);
        }
    }
}
