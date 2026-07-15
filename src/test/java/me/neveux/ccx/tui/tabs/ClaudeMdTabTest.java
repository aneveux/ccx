package me.neveux.ccx.tui.tabs;

import me.neveux.ccx.tui.TuiSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClaudeMdTab")
class ClaudeMdTabTest {

    @Test
    @DisplayName("empty-state message must reference the hyphenated `ccx claude-md` command")
    void emptyStateMessage_usesHyphenatedClaudeMdCommand() {
        var snapshot = new TuiSnapshot(
            TuiSnapshot.OverviewData.empty(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );

        var message = new ClaudeMdTab().emptyStateMessage(snapshot);

        assertThat(message)
            .as("empty-state hint must use the real hyphenated command name")
            .contains("ccx claude-md")
            .doesNotContain("ccx claudemd list");
    }
}
