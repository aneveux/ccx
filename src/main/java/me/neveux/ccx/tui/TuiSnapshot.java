package me.neveux.ccx.tui;

import me.neveux.ccx.model.McpServer;
import me.neveux.ccx.model.ProfileDriftState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full immutable data graph for one TUI render frame. Produced by {@link TuiDataService}
 * after every mutation and passed down to every tab renderer. Carrying the entire
 * relevant disk state in one value means tab renderers are pure functions of
 * ({@code TuiSnapshot}, {@link TuiState}) with no direct service dependencies.
 *
 * <p>Nested types represent the per-domain data slices:
 * <ul>
 *   <li>{@link ProfileEntry} — a profile with its current drift state and preview Markdown</li>
 *   <li>{@link PluginEntry} — a plugin qualified name with its enable scope</li>
 *   <li>{@link McpEntry} — a named MCP server and its configuration</li>
 *   <li>{@link StackEntry} — one line from the CLAUDE.md marker block, with source classification</li>
 *   <li>{@link OverviewData} — aggregate counts and file-presence flags for the Overview tab</li>
 *   <li>{@link ClaudeIgnoreInfo} — presence and line counts of project/global {@code .claudeignore}</li>
 * </ul>
 */
public record TuiSnapshot(
    OverviewData overview,
    List<ProfileEntry> profiles,
    List<PluginEntry> plugins,
    List<McpEntry> mcps,
    List<StackEntry> claudeMdEntries
) {
    /** A profile discovered on disk, with its computed drift state and pre-rendered preview. */
    public record ProfileEntry(
        String name,
        String description,
        ProfileDriftState driftState,
        String previewMarkdown
    ) {}

    /** A plugin entry combining its qualified name, short display name, marketplace, and enable scope. */
    public record PluginEntry(
        String qualifiedName,
        String shortName,
        String marketplace,
        PluginStatus status,
        String description
    ) {}

    /** A named MCP server entry with its transport configuration. */
    public record McpEntry(
        String name,
        McpServer config,
        boolean enabled
    ) {}

    /** One entry from the CLAUDE.md marker block, with its source classification and optional preview Markdown. */
    public record StackEntry(
        String line,
        String name,
        StackSource source,
        String previewMarkdown
    ) {}

    public enum StackSource { PROJECT, FRAGMENT, AVAILABLE, MISSING }

    /**
     * Aggregate data for the Overview tab. Built by {@link TuiDataService} from counts,
     * file-presence flags, and name lists — everything the Overview tab renders without
     * needing to repeat the raw plugin/profile/mcp lists.
     */
    public record OverviewData(
        List<String> enabledProfileNames,
        Map<String, ProfileDriftState> profileDriftStates,
        int projectPluginCount,
        int globalPluginCount,
        int mcpCount,
        ClaudeIgnoreInfo claudeIgnore,
        int hookCount,
        boolean rulesExists,
        int rulesCount,
        List<String> projectPluginNames,
        List<String> globalPluginNames,
        List<String> rulesFileNames,
        String contextFilePath,
        String projectClaudeMdPath,
        String globalClaudeMdPath,
        int globalHookCount,
        String privateClaudeMdPath,
        boolean projectSettingsExists,
        List<String> fragmentNames,
        List<String> mcpServerNames
    ) {
        public static OverviewData empty() {
            return new Builder().build();
        }

        public static final class Builder {
            private List<String> enabledProfileNames = List.of();
            private Map<String, ProfileDriftState> profileDriftStates = Map.of();
            private int projectPluginCount = 0;
            private int globalPluginCount = 0;
            private int mcpCount = 0;
            private ClaudeIgnoreInfo claudeIgnore = new ClaudeIgnoreInfo(false, false, 0, 0);
            private int hookCount = 0;
            private boolean rulesExists = false;
            private int rulesCount = 0;
            private List<String> projectPluginNames = List.of();
            private List<String> globalPluginNames = List.of();
            private List<String> rulesFileNames = List.of();
            private String contextFilePath = null;
            private String projectClaudeMdPath = null;
            private String globalClaudeMdPath = null;
            private int globalHookCount = 0;
            private String privateClaudeMdPath = null;
            private boolean projectSettingsExists = false;
            private List<String> fragmentNames = List.of();
            private List<String> mcpServerNames = List.of();

            public Builder enabledProfileNames(List<String> v) { this.enabledProfileNames = v; return this; }
            public Builder profileDriftStates(Map<String, ProfileDriftState> v) { this.profileDriftStates = v; return this; }
            public Builder projectPluginCount(int v) { this.projectPluginCount = v; return this; }
            public Builder globalPluginCount(int v) { this.globalPluginCount = v; return this; }
            public Builder mcpCount(int v) { this.mcpCount = v; return this; }
            public Builder claudeIgnore(ClaudeIgnoreInfo v) { this.claudeIgnore = v; return this; }
            public Builder hookCount(int v) { this.hookCount = v; return this; }
            public Builder rulesExists(boolean v) { this.rulesExists = v; return this; }
            public Builder rulesCount(int v) { this.rulesCount = v; return this; }
            public Builder projectPluginNames(List<String> v) { this.projectPluginNames = v; return this; }
            public Builder globalPluginNames(List<String> v) { this.globalPluginNames = v; return this; }
            public Builder rulesFileNames(List<String> v) { this.rulesFileNames = v; return this; }
            public Builder contextFilePath(String v) { this.contextFilePath = v; return this; }
            public Builder projectClaudeMdPath(String v) { this.projectClaudeMdPath = v; return this; }
            public Builder globalClaudeMdPath(String v) { this.globalClaudeMdPath = v; return this; }
            public Builder globalHookCount(int v) { this.globalHookCount = v; return this; }
            public Builder privateClaudeMdPath(String v) { this.privateClaudeMdPath = v; return this; }
            public Builder projectSettingsExists(boolean v) { this.projectSettingsExists = v; return this; }
            public Builder fragmentNames(List<String> v) { this.fragmentNames = v; return this; }
            public Builder mcpServerNames(List<String> v) { this.mcpServerNames = v; return this; }

            public OverviewData build() {
                return new OverviewData(
                    enabledProfileNames, profileDriftStates, projectPluginCount, globalPluginCount,
                    mcpCount, claudeIgnore, hookCount, rulesExists, rulesCount,
                    projectPluginNames, globalPluginNames, rulesFileNames, contextFilePath,
                    projectClaudeMdPath, globalClaudeMdPath, globalHookCount,
                    privateClaudeMdPath, projectSettingsExists, fragmentNames, mcpServerNames
                );
            }
        }
    }

    /** Presence and non-comment line counts for project-level and global {@code .claudeignore} files. */
    public record ClaudeIgnoreInfo(
        boolean presentGlobal,
        boolean presentProject,
        int globalEntryCount,
        int projectEntryCount
    ) {}

    public enum PluginStatus   { PROJECT_ENABLED, GLOBALLY_ENABLED, AVAILABLE }

    /** Raw stack lines from the ccx marker block (for TuiActions.removeStack / OverviewTab). */
    public List<String> stacks() {
        return claudeMdEntries.stream()
            .filter(e -> e.source() != StackSource.AVAILABLE)
            .map(StackEntry::line)
            .toList();
    }

    /** MCP servers keyed by name (for TuiActions.toggleMcp). */
    public Map<String, McpServer> mcpServers() {
        var result = new LinkedHashMap<String, McpServer>();
        mcps.forEach(e -> result.put(e.name(), e.config()));
        return result;
    }
}
