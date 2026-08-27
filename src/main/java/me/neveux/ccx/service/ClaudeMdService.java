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
 * exist, a new block is appended at the end of the file. A file that somehow ends up with
 * several blocks, an unterminated block, or an inverted pair (end before begin) is
 * normalised back to exactly one well-formed block on the next write.
 *
 * <p>All writes are atomic via {@link me.neveux.ccx.util.FileUtils#atomicWrite}.
 */
@ApplicationScoped
public class ClaudeMdService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeMdService.class);

    private static final String BEGIN_MARKER = "<!-- ccx:begin -->";
    private static final String END_MARKER = "<!-- ccx:end -->";

    /**
     * Returns the lines inside the <em>first</em> {@code <!-- ccx:begin -->} block of the
     * given CLAUDE.md file, with the marker lines themselves excluded. Returns an empty
     * list if the file does not exist, cannot be read, or contains no ccx markers.
     *
     * <p>Only the first block is read: it is the one {@link #writeMarkerBlock} keeps. Any
     * further block is debris and is reported as absent so callers never see a merge of
     * two generations of managed content. A block with no end marker extends to EOF.
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
                if (!inside) {
                    if (line.contains(BEGIN_MARKER)) {
                        inside = true;
                    }
                    continue;
                }
                if (line.contains(END_MARKER)) {
                    return result;
                }
                result.add(line);
            }
            return result;
        } catch (IOException e) {
            log.warn("failed to read CLAUDE.md path={}", claudeMdFile, e);
            return List.of();
        }
    }

    /**
     * Replaces the content of the ccx block in {@code claudeMdFile} with {@code stackLines},
     * leaving the file with exactly one well-formed block. If the file does not exist, it is
     * created containing only the ccx block. If no markers are present, the block is appended
     * at the end. Every marker line and every line inside a managed block is dropped first, so
     * duplicated, unterminated, or inverted blocks collapse into the single block written here,
     * at the position the first begin marker held.
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

            var stripped = stripManagedBlocks(Files.readAllLines(claudeMdFile));
            var output = new ArrayList<>(stripped.lines());
            var insertAt = stripped.insertAt();
            if (insertAt == -1) {
                if (!output.isEmpty() && !output.get(output.size() - 1).isBlank()) {
                    output.add("");
                }
                insertAt = output.size();
            }

            var block = new ArrayList<String>();
            block.add(BEGIN_MARKER);
            block.addAll(stackLines);
            block.add(END_MARKER);
            output.addAll(insertAt, block);

            var updated = String.join("\n", output) + "\n";
            FileUtils.atomicWrite(claudeMdFile, updated);
            log.debug("updated CLAUDE.md ccx block path={}", claudeMdFile);
        } catch (IOException e) {
            throw new ConfigWriteException("Failed to write CLAUDE.md at " + claudeMdFile, e);
        }
    }

    /**
     * The user-owned lines of a CLAUDE.md, plus where the managed block belongs.
     *
     * @param lines    every line ccx does not own, in order
     * @param insertAt index into {@code lines} where the first begin marker stood, or -1 when
     *                 the file carried no begin marker and the block has to be appended
     */
    private record StrippedContent(List<String> lines, int insertAt) {}

    private static StrippedContent stripManagedBlocks(List<String> existing) {
        var lines = repairInvertedMarkers(existing);
        var kept = new ArrayList<String>();
        var insertAt = -1;
        var inside = false;
        for (var line : lines) {
            if (inside) {
                if (line.contains(END_MARKER)) {
                    inside = false;
                }
                continue;
            }
            if (line.contains(BEGIN_MARKER)) {
                inside = true;
                if (insertAt == -1) {
                    insertAt = kept.size();
                }
                continue;
            }
            if (line.contains(END_MARKER)) {
                continue;
            }
            kept.add(line);
        }
        return new StrippedContent(kept, insertAt);
    }

    /**
     * Drops the debris of an interrupted write: an end marker with no begin marker before it,
     * together with everything up to and including the next begin marker. What sits between an
     * inverted pair is a previous generation of managed content, not something the user typed.
     */
    private static List<String> repairInvertedMarkers(List<String> lines) {
        var firstBegin = indexOfMarker(lines, BEGIN_MARKER);
        var firstEnd = indexOfMarker(lines, END_MARKER);
        if (firstEnd == -1 || (firstBegin != -1 && firstBegin < firstEnd)) {
            return lines;
        }
        var lastDropped = firstBegin != -1 ? firstBegin : firstEnd;
        var repaired = new ArrayList<>(lines);
        repaired.subList(firstEnd, lastDropped + 1).clear();
        return repaired;
    }

    private static int indexOfMarker(List<String> lines, String marker) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(marker)) {
                return i;
            }
        }
        return -1;
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
