// Wave 4 — F39: TuiCommand early-exit guard (null services)
package me.neveux.ccx.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TuiCommand — early-exit guard, null services")
class TuiCommandTest {

    @Test
    @DisplayName("exits 1 with a clear message when services are unavailable")
    void tui_nullServices_exitsWithError() {
        var buf = new ByteArrayOutputStream();
        // No-arg constructor leaves all services null (CDI injection failed scenario).
        var cmd = new TuiCommand();
        var cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(buf, true));
        cl.setErr(new PrintWriter(buf, true));

        int exit = cl.execute();

        assertThat(exit)
            .as("TUI must exit 1 when its services are null")
            .isEqualTo(1);
        assertThat(buf.toString())
            .as("TUI must explain that its services are unavailable")
            .containsIgnoringCase("services unavailable");
    }
}
