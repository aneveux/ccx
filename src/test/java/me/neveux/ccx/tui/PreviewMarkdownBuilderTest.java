package me.neveux.ccx.tui;

import me.neveux.ccx.model.Permissions;
import me.neveux.ccx.model.Profile;
import me.neveux.ccx.model.ProfileDriftState;
import me.neveux.ccx.model.ProfilePlugins;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PreviewMarkdownBuilder")
class PreviewMarkdownBuilderTest {

    @TempDir
    Path profileDir;

    private static final String EMPTY_PLACEHOLDER = Quips.EMPTY[0] + " nothing here";

    @Nested
    @DisplayName("drift-state status icon")
    class StatusIcon {

        @Test
        @DisplayName("renders enabled icon for ENABLED state")
        void profilePreview_when_enabled_then_greenIcon() {
            var md = PreviewMarkdownBuilder.profilePreview("p", new Profile(), ProfileDriftState.ENABLED, profileDir);

            assertThat(md)
                .contains("### Status")
                .contains("💚")
                .contains("enabled");
        }

        @Test
        @DisplayName("renders drifted icon for ALTERED state")
        void profilePreview_when_altered_then_orangeIcon() {
            var md = PreviewMarkdownBuilder.profilePreview("p", new Profile(), ProfileDriftState.ALTERED, profileDir);

            assertThat(md)
                .contains("### Status")
                .contains("💛")
                .doesNotContain("🟠")
                .contains("drifted");
        }

        @Test
        @DisplayName("renders disabled icon for DISABLED state")
        void profilePreview_when_disabled_then_circleIcon() {
            var md = PreviewMarkdownBuilder.profilePreview("p", new Profile(), ProfileDriftState.DISABLED, profileDir);

            assertThat(md)
                .contains("### Status")
                .contains("🤍")
                .doesNotContain("○")
                .contains("disabled");
        }

        @Test
        @DisplayName("renders broken icon for BROKEN state")
        void profilePreview_when_broken_then_redIcon() {
            var md = PreviewMarkdownBuilder.profilePreview("p", new Profile(), ProfileDriftState.BROKEN, profileDir);

            assertThat(md)
                .contains("### Status")
                .contains("💔")
                .contains("broken");
        }
    }

    @Nested
    @DisplayName("populated sections")
    class Populated {

        @Test
        @DisplayName("renders each section with its values when the profile is fully populated")
        void profilePreview_when_populated_then_allSectionsRendered() {
            var profile = new Profile()
                .withPlugins(new ProfilePlugins(List.of("a@mkt")))
                .withExtend(List.of("base"))
                .withPermissions(new Permissions(List.of("Bash"), null))
                .withEnv(Map.of("K", "V"))
                .withIgnore(List.of("*.log"));

            var md = PreviewMarkdownBuilder.profilePreview("p", profile, ProfileDriftState.ENABLED, profileDir);

            assertThat(md)
                .contains("### Plugins").contains("`a@mkt`")
                .contains("### Extends").contains("base")
                .contains("### Permissions").contains("Bash")
                .contains("### Environment").contains("`K=V`")
                .contains("### Ignore").contains("`*.log`");
        }
    }

    @Nested
    @DisplayName("empty profile")
    class Empty {

        @Test
        @DisplayName("falls back to the empty placeholder for plugins/extends/permissions/claudemd/rules")
        void profilePreview_when_empty_then_placeholderSections() {
            var md = PreviewMarkdownBuilder.profilePreview("p", new Profile(), ProfileDriftState.ENABLED, profileDir);

            assertThat(md)
                .contains("### Plugins")
                .contains("### Extends")
                .contains("### Permissions")
                .contains("### CLAUDE.md Fragments")
                .contains("### Rules")
                .contains(EMPTY_PLACEHOLDER);
        }

        @Test
        @DisplayName("omits the Environment and Ignore headers entirely when empty")
        void profilePreview_when_empty_then_envAndIgnoreHeadersAbsent() {
            var md = PreviewMarkdownBuilder.profilePreview("p", new Profile(), ProfileDriftState.ENABLED, profileDir);

            assertThat(md)
                .doesNotContain("### Environment")
                .doesNotContain("### Ignore");
        }
    }

    @Nested
    @DisplayName("CLAUDE.md fragments disk listing")
    class ClaudeMdFragments {

        @Test
        @DisplayName("lists claudemd/*.md files sorted alphabetically")
        void profilePreview_when_claudemdDirHasFiles_then_listsSorted() throws Exception {
            var dir = profileDir.resolve("claudemd");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("quarkus.md"), "x");
            Files.writeString(dir.resolve("java.md"), "y");

            var md = PreviewMarkdownBuilder.profilePreview("p", new Profile(), ProfileDriftState.ENABLED, profileDir);

            assertThat(md).contains("java.md").contains("quarkus.md");
            assertThat(md.indexOf("java.md"))
                .as("java.md must be listed before quarkus.md (sorted)")
                .isLessThan(md.indexOf("quarkus.md"));
        }

        @Test
        @DisplayName("falls back to the empty placeholder when claudemd dir is absent")
        void profilePreview_when_claudemdDirAbsent_then_placeholder() {
            var md = PreviewMarkdownBuilder.profilePreview("p", new Profile(), ProfileDriftState.ENABLED, profileDir);

            // Section heading present, with the placeholder beneath it.
            assertThat(md).contains("### CLAUDE.md Fragments").contains(EMPTY_PLACEHOLDER);
        }
    }

    @Nested
    @DisplayName("rules disk listing")
    class Rules {

        @Test
        @DisplayName("lists only .md files, ignoring non-markdown files")
        void profilePreview_when_rulesDirMixed_then_onlyMarkdown() throws Exception {
            var dir = profileDir.resolve("rules");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("security.md"), "x");
            Files.writeString(dir.resolve("notes.txt"), "y");

            var md = PreviewMarkdownBuilder.profilePreview("p", new Profile(), ProfileDriftState.ENABLED, profileDir);

            assertThat(md).contains("security.md").doesNotContain("notes.txt");
        }
    }

    @Nested
    @DisplayName("missingStackPreview")
    class MissingStack {

        @Test
        @DisplayName("embeds the referenced line and a 'not found' title")
        void missingStackPreview_when_called_then_containsLineAndNotFound() {
            var md = PreviewMarkdownBuilder.missingStackPreview("@some/fragment.md");

            assertThat(md)
                .contains("not found")
                .contains("@some/fragment.md");
        }
    }
}
