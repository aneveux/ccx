package me.neveux.ccx.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClaudeMdService")
class ClaudeMdServiceTest {

    private ClaudeMdService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new ClaudeMdService();
    }

    // -----------------------------------------------------------------------
    // writeMarkerBlock()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("writeMarkerBlock()")
    class WriteMarkerBlock {

        @Test
        @DisplayName("should create CLAUDE.md with begin and end markers when file is absent")
        void writeMarkerBlock_createsFileWhenAbsent() throws Exception {
            var claudeMd = tempDir.resolve("CLAUDE.md");

            service.writeMarkerBlock(claudeMd, List.of("@~/.claude/stacks/java.md"));

            assertThat(claudeMd).exists();
            var content = Files.readString(claudeMd);
            assertThat(content)
                .as("written file must contain begin marker")
                .contains("<!-- ccx:begin -->");
            assertThat(content)
                .as("written file must contain end marker")
                .contains("<!-- ccx:end -->");
        }

        @Test
        @DisplayName("should append marker block to existing file that has no markers")
        void writeMarkerBlock_appendsToExistingFileWithoutMarkers() throws Exception {
            var claudeMd = tempDir.resolve("CLAUDE.md");
            var originalContent = "# My Project\n\nExisting content here.\n";
            Files.writeString(claudeMd, originalContent);

            service.writeMarkerBlock(claudeMd, List.of("@~/.claude/stacks/java.md"));

            var content = Files.readString(claudeMd);
            assertThat(content)
                .as("original content must be preserved when block is appended")
                .contains("# My Project");
            assertThat(content)
                .as("original content must appear before the ccx block")
                .contains("Existing content here.");
            assertThat(content)
                .as("begin marker must be present")
                .contains("<!-- ccx:begin -->");
        }

        @Test
        @DisplayName("should replace existing marker block content without touching content outside")
        void writeMarkerBlock_replacesExistingBlock() throws Exception {
            var claudeMd = tempDir.resolve("CLAUDE.md");
            var existingContent = """
                # My Project

                Before block.

                <!-- ccx:begin -->
                @~/.claude/stacks/old-stack.md
                <!-- ccx:end -->

                After block.
                """;
            Files.writeString(claudeMd, existingContent);

            service.writeMarkerBlock(claudeMd, List.of("@~/.claude/stacks/new-stack.md"));

            var content = Files.readString(claudeMd);
            assertThat(content)
                .as("old stack reference must be replaced")
                .doesNotContain("old-stack.md");
            assertThat(content)
                .as("new stack reference must be present")
                .contains("new-stack.md");
            assertThat(content)
                .as("content before block must be unchanged")
                .contains("Before block.");
            assertThat(content)
                .as("content after block must be unchanged")
                .contains("After block.");
        }
    }

    // -----------------------------------------------------------------------
    // inverted markers (END before BEGIN) must be repaired
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("writeMarkerBlock() with inverted markers")
    class WriteWithInvertedMarkers {

        @Test
        @DisplayName("should produce exactly one well-formed marker block when markers are inverted")
        void writeMarkerBlock_repairsInvertedMarkers() throws Exception {
            var claudeMd = tempDir.resolve("CLAUDE.md");
            // Malformed file: END marker appears BEFORE the BEGIN marker.
            var malformed = """
                # Header

                <!-- ccx:end -->
                @~/.claude/stacks/stale.md
                <!-- ccx:begin -->

                Footer
                """;
            Files.writeString(claudeMd, malformed);

            service.writeMarkerBlock(claudeMd, List.of("@~/.claude/stacks/java.md"));

            var content = Files.readString(claudeMd);

            long beginCount = content.lines().filter(l -> l.contains("<!-- ccx:begin -->")).count();
            long endCount = content.lines().filter(l -> l.contains("<!-- ccx:end -->")).count();

            assertThat(beginCount)
                .as("after repair there must be exactly one begin marker — malformed markers removed, not duplicated")
                .isEqualTo(1);
            assertThat(endCount)
                .as("after repair there must be exactly one end marker — malformed markers removed, not duplicated")
                .isEqualTo(1);
            assertThat(content.indexOf("<!-- ccx:begin -->"))
                .as("the surviving block must be well-formed: begin must precede end")
                .isLessThan(content.indexOf("<!-- ccx:end -->"));
            assertThat(content)
                .as("the new stack line must be present inside the repaired block")
                .contains("@~/.claude/stacks/java.md");
        }
    }

    // -----------------------------------------------------------------------
    // readMarkerBlock()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("readMarkerBlock()")
    class ReadMarkerBlock {

        @Test
        @DisplayName("should return empty list when the file does not exist")
        void readMarkerBlock_returnsEmpty_whenFileAbsent() {
            var nonExistent = tempDir.resolve("does-not-exist.md");

            var lines = service.readMarkerBlock(nonExistent);

            assertThat(lines)
                .as("readMarkerBlock must return an empty list when the file does not exist")
                .isEmpty();
        }

        @Test
        @DisplayName("should return partial lines when begin marker is present but end marker is absent")
        void readMarkerBlock_handlesUnclosedMarker() throws Exception {
            var claudeMd = tempDir.resolve("CLAUDE.md");
            Files.writeString(claudeMd, """
                # Header
                <!-- ccx:begin -->
                @~/.claude/stacks/java.md
                """);

            var lines = service.readMarkerBlock(claudeMd);

            // The implementation collects lines after begin marker until EOF (no end marker found),
            // so lines inside the unclosed block are returned as-is.
            assertThat(lines)
                .as("readMarkerBlock with unclosed begin marker must return lines collected up to EOF")
                .contains("@~/.claude/stacks/java.md");
        }

        @Test
        @DisplayName("should return only lines inside markers, not the marker lines themselves")
        void readMarkerBlock_returnsLinesInsideMarkers() throws Exception {
            var claudeMd = tempDir.resolve("CLAUDE.md");
            var content = """
                # Header

                <!-- ccx:begin -->
                @~/.claude/stacks/java.md
                @~/.claude/stacks/java-core.md
                <!-- ccx:end -->

                Footer
                """;
            Files.writeString(claudeMd, content);

            var lines = service.readMarkerBlock(claudeMd);

            assertThat(lines)
                .as("read must return exactly the two stack lines inside markers")
                .hasSize(2)
                .contains("@~/.claude/stacks/java.md")
                .contains("@~/.claude/stacks/java-core.md")
                .noneMatch(line -> line.contains("ccx:begin") || line.contains("ccx:end"));
        }

        @Test
        @DisplayName("should return empty list when no markers are present")
        void readMarkerBlock_returnsEmptyWhenNoMarkers() throws Exception {
            var claudeMd = tempDir.resolve("CLAUDE.md");
            Files.writeString(claudeMd, "# No markers here\n");

            var lines = service.readMarkerBlock(claudeMd);

            assertThat(lines)
                .as("read must return empty list when no ccx markers found")
                .isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // writeMarkerBlock() — boundary: empty lines inside block
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("writeMarkerBlock() with empty block")
    class WriteEmptyBlock {

        @Test
        @DisplayName("should not touch content before or after block when writing empty line list")
        void cleanDoesNotTouchContentOutsideMarkers() throws Exception {
            var claudeMd = tempDir.resolve("CLAUDE.md");
            var existingContent = """
                # Header

                Content before.

                <!-- ccx:begin -->
                @~/.claude/stacks/java.md
                <!-- ccx:end -->

                Content after.
                """;
            Files.writeString(claudeMd, existingContent);

            service.writeMarkerBlock(claudeMd, List.of());

            var content = Files.readString(claudeMd);
            assertThat(content)
                .as("content before block must be preserved after writeMarkerBlock with empty list")
                .contains("Content before.");
            assertThat(content)
                .as("content after block must be preserved after writeMarkerBlock with empty list")
                .contains("Content after.");
            assertThat(content)
                .as("old stack line must be removed when writing empty list")
                .doesNotContain("@~/.claude/stacks/java.md");
        }
    }
}
