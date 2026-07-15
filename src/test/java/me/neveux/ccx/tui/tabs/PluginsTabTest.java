package me.neveux.ccx.tui.tabs;

import me.neveux.ccx.tui.TuiSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PluginsTab")
class PluginsTabTest {

    /**
     * Invokes the private static scopeLabel(PluginStatus) via reflection.
     * PluginsTab is final so subclassing is not possible.
     */
    private static String scopeLabel(TuiSnapshot.PluginStatus status) throws Exception {
        Method m = PluginsTab.class.getDeclaredMethod("scopeLabel", TuiSnapshot.PluginStatus.class);
        m.setAccessible(true);
        return (String) m.invoke(null, status);
    }

    @Nested
    @DisplayName("scopeLabel()")
    class ScopeLabel {

        @Test
        @DisplayName("scopeLabel for PROJECT_ENABLED must use a filled circle icon, not a letter")
        void scopeLabel_projectEnabled_usesIcon() throws Exception {
            var label = scopeLabel(TuiSnapshot.PluginStatus.PROJECT_ENABLED);

            assertThat(label)
                .as("PROJECT_ENABLED scope label must use emoji '💚', not a geometric glyph")
                .contains("💚");
            assertThat(label)
                .as("PROJECT_ENABLED scope label must not start with the legacy letter 'P'")
                .doesNotStartWith("P");
        }

        @Test
        @DisplayName("scopeLabel for GLOBALLY_ENABLED must use a circle icon, not a letter")
        void scopeLabel_globallyEnabled_usesIcon() throws Exception {
            var label = scopeLabel(TuiSnapshot.PluginStatus.GLOBALLY_ENABLED);

            assertThat(label)
                .as("GLOBALLY_ENABLED scope label must use emoji '💙', not a geometric glyph")
                .contains("💙");
            assertThat(label)
                .as("GLOBALLY_ENABLED scope label must not start with the legacy letter 'G'")
                .doesNotStartWith("G");
        }
    }
}
