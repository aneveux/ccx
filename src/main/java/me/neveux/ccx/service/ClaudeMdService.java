package me.neveux.ccx.service;

import me.neveux.ccx.util.FileUtils;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the {@code <!-- ccx:begin -->...<!-- ccx:end -->} managed block inside
 * a project's {@code .claude/CLAUDE.md} file.
 *
 * <p>Everything between the two HTML-comment markers is owned by ccx and replaced on each
 * profile enable/disable. Content outside the markers is left untouched. If no markers
 * exist, a new block is appended at the end of the file. Malformed marker pairs (end
 * before begin) are detected and cleaned up automatically.
 *
 * <p>All writes are atomic via {@link me.neveux.ccx.util.FileUtils#atomicWrite}.
 */
@ApplicationScoped
public class ClaudeMdService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeMdService.class);

    private static final String BEGIN_MARKER = "<!-- ccx:begin -->";
    private static final String END_MARKER = "<!-- ccx:end -->";

    /**
     * Returns the lines currently inside the {@code <!-- ccx:begin -->} block of the
     * given CLAUDE.md file, with the marker lines themselves excluded. Returns an empty
     * list if the file does not exist, cannot be read, or contains no ccx markers.
     *
     * @param claudeMdFile path to the CLAUDE.md file (typically {@code .claude/CLAUDE.md})
     * @return the lines between the ccx markers; never null
     */
    public List<String> readMarkerBlock(Path claudeMdFile) {
        if (!Files.exists(claudeMdFile)) {
            return List.of();
        }
        try {
            var lines = Files.readAllLines(claudeMdFile);
            var inside = false;
            var result = new ArrayList<String>();
            for (var line : lines) {
                if (line.contains(BEGIN_MARKER)) {
                    inside = true;
                    continue;
                }
                if (line.contains(END_MARKER)) {
                    inside = false;
                    continue;
                }
                if (inside) {
                    result.add(line);
                }
            }
            return result;
        } catch (IOException e) {
            log.warn("failed to read CLAUDE.md path={}", claudeMdFile, e);
            return List.of();
        }
    }

    /**
     * Replaces the content of the ccx block in {@code claudeMdFile} with {@code stackLines}.
     * If the file does not exist, it is created containing only the ccx block. If no
     * markers are present, the block is appended at the end. Malformed markers (end before
     * begin) are stripped and a clean block is appended.
     *
     * @param claudeMdFile path to the CLAUDE.md file (typically {@code .claude/CLAUDE.md})
     * @param stackLines   the lines to write between the ccx markers; must not be null
     * @throws ConfigWriteException if the file cannot be written
     */
    public void writeMarkerBlock(Path claudeMdFile, List<String> stackLines) {
        try {
            if (!Files.exists(claudeMdFile)) {
                var content = buildBlock(stackLines);
                FileUtils.atomicWrite(claudeMdFile, content);
                log.debug("created CLAUDE.md with ccx block path={}", claudeMdFile);
                return;
            }

            var existing = Files.readAllLines(claudeMdFile);
            var beginIdx = -1;
            var endIdx = -1;
            for (int i = 0; i < existing.size(); i++) {
                if (existing.get(i).contains(BEGIN_MARKER)) beginIdx = i;
                if (existing.get(i).contains(END_MARKER)) endIdx = i;
            }

            List<String> source = existing;
            if (beginIdx != -1 && endIdx != -1 && beginIdx >= endIdx) {
                // Malformed: end marker appears before begin marker. Strip every marker
                // line and any content trapped between the inverted markers, then append
                // a single well-formed block below.
                var cleaned = new ArrayList<String>();
                for (int i = 0; i < existing.size(); i++) {
                    if (i >= endIdx && i <= beginIdx) continue;
                    cleaned.add(existing.get(i));
                }
                source = cleaned;
                beginIdx = -1;
                endIdx = -1;
            }

            List<String> output;
            if (beginIdx == -1 || endIdx == -1) {
                // No markers — append block at end
                output = new ArrayList<>(source);
                if (!output.isEmpty() && !output.get(output.size() - 1).isBlank()) {
                    output.add("");
                }
                output.add(BEGIN_MARKER);
                output.addAll(stackLines);
                output.add(END_MARKER);
            } else {
                // Replace content between markers
                output = new ArrayList<>();
                output.addAll(existing.subList(0, beginIdx + 1)); // up to and including begin marker
                output.addAll(stackLines);
                output.addAll(existing.subList(endIdx, existing.size())); // from end marker onwards
            }

            var updated = String.join("\n", output) + "\n";
            FileUtils.atomicWrite(claudeMdFile, updated);
            log.debug("updated CLAUDE.md ccx block path={}", claudeMdFile);
        } catch (IOException e) {
            throw new ConfigWriteException("Failed to write CLAUDE.md at " + claudeMdFile, e);
        }
    }

    private String buildBlock(List<String> lines) {
        var sb = new StringBuilder();
        sb.append(BEGIN_MARKER).append("\n");
        for (var line : lines) {
            sb.append(line).append("\n");
        }
        sb.append(END_MARKER).append("\n");
        return sb.toString();
    }
}
