package me.neveux.ccx.tui;

import me.neveux.ccx.model.Profile;
import me.neveux.ccx.model.ProfileDriftState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds the pre-composed Markdown shown in the TUI preview panes (ADR-010).
 * Pure formatting from a {@link Profile} and on-disk profile directory — extracted
 * from {@code TuiDataService} so the snapshot service stays focused on data loading.
 */
final class PreviewMarkdownBuilder {

    private static final Logger log = LoggerFactory.getLogger(PreviewMarkdownBuilder.class);

    private PreviewMarkdownBuilder() {}

    static String profilePreview(String name, Profile profile, ProfileDriftState driftState, Path profileDir) {
        var sb = new StringBuilder();
        sb.append("## ").append(name).append("\n");

        if (profile.metadata() != null && profile.metadata().description() != null && !profile.metadata().description().isBlank()) {
            sb.append(profile.metadata().description()).append("\n");
        }
        sb.append("\n");

        var statusIcon = switch (driftState) {
            case ENABLED  -> Glyphs.ENABLED + "  enabled";
            case ALTERED  -> Glyphs.DRIFTED + "  drifted";
            case BROKEN   -> Glyphs.BROKEN + "  broken — missing dependency";
            case DISABLED -> Glyphs.AVAILABLE + "  disabled";
        };
        sb.append("### Status\n").append(statusIcon).append("\n\n");

        var extendsChain = (profile.extend() != null && !profile.extend().isEmpty())
            ? String.join(", ", profile.extend())
            : Quips.EMPTY[0] + " nothing here";
        sb.append("### Extends\n").append(extendsChain).append("\n\n");

        var pluginsList = (profile.plugins() != null && profile.plugins().enable() != null)
            ? profile.plugins().enable() : List.<String>of();
        sb.append("### Plugins\n\n");
        if (pluginsList.isEmpty()) {
            sb.append("_").append(Quips.EMPTY[0]).append(" nothing here_\n");
        } else {
            pluginsList.forEach(p -> sb.append("- `").append(p).append("`\n"));
        }
        sb.append("\n");

        sb.append("### CLAUDE.md Fragments\n\n");
        var claudeMdFragDir = profileDir.resolve("claudemd");
        if (Files.isDirectory(claudeMdFragDir)) {
            try (var stream = Files.list(claudeMdFragDir)) {
                var files = stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
                if (files.isEmpty()) {
                    sb.append("_").append(Quips.EMPTY[0]).append(" nothing here_\n");
                } else {
                    files.forEach(f -> sb.append("- `").append(f).append("`\n"));
                }
            } catch (IOException e) {
                log.debug("failed to list profile claudemd dir path={}", claudeMdFragDir, e);
                sb.append("_").append(Quips.EMPTY[0]).append(" nothing here_\n");
            }
        } else {
            sb.append("_").append(Quips.EMPTY[0]).append(" nothing here_\n");
        }
        sb.append("\n");

        sb.append("### Rules\n\n");
        var rulesFragDir = profileDir.resolve("rules");
        if (Files.isDirectory(rulesFragDir)) {
            try (var stream = Files.list(rulesFragDir)) {
                var files = stream.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".md"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
                if (files.isEmpty()) {
                    sb.append("_").append(Quips.EMPTY[0]).append(" nothing here_\n");
                } else {
                    files.forEach(f -> sb.append("- ").append(f).append("\n"));
                }
            } catch (IOException e) {
                log.debug("failed to list profile rules dir path={}", rulesFragDir, e);
                sb.append("_").append(Quips.EMPTY[0]).append(" nothing here_\n");
            }
        } else {
            sb.append("_").append(Quips.EMPTY[0]).append(" nothing here_\n");
        }
        sb.append("\n");

        var allowList = (profile.permissions() != null && profile.permissions().allow() != null)
            ? profile.permissions().allow() : List.<String>of();
        sb.append("### Permissions\n\n");
        if (allowList.isEmpty()) {
            sb.append("_").append(Quips.EMPTY[0]).append(" nothing here_\n");
        } else {
            allowList.forEach(a -> sb.append("- ").append(a).append("\n"));
        }
        sb.append("\n");

        if (profile.env() != null && !profile.env().isEmpty()) {
            sb.append("### Environment\n\n");
            profile.env().forEach((k, v) -> sb.append("- `").append(k).append("=").append(v).append("`\n"));
            sb.append("\n");
        }

        if (profile.ignore() != null && !profile.ignore().isEmpty()) {
            sb.append("### Ignore\n\n");
            profile.ignore().forEach(p -> sb.append("- `").append(p).append("`\n"));
            sb.append("\n");
        }

        appendProfileFiles(sb, profileDir.resolve("commands"), "Command files");

        return sb.toString();
    }

    private static void appendProfileFiles(StringBuilder sb, Path dir, String label) {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            var files = stream.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .sorted()
                .toList();
            if (!files.isEmpty()) {
                sb.append("**").append(label).append("**\n");
                files.forEach(f -> sb.append("- ").append(f).append("\n"));
                sb.append("\n");
            }
        } catch (IOException e) {
            log.debug("failed to list profile subdir path={}", dir, e);
        }
    }

    static String missingStackPreview(String line) {
        return "**Fragment file not found**\n\n" +
            "This CLAUDE.md fragment is referenced in your CLAUDE.md marker block " +
            "(`" + line + "`) but the file does not exist on disk.\n\n" +
            "This may have been installed by a profile that is no longer active, " +
            "or the fragment file was deleted. " +
            "Check `~/.config/ccx/claudemd/` for available fragments, " +
            "or remove this entry from your CLAUDE.md.";
    }
}
