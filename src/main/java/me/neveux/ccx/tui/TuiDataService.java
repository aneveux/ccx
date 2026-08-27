package me.neveux.ccx.tui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.neveux.ccx.config.ObjectMapperConfig;
import me.neveux.ccx.model.CcxContext;
import me.neveux.ccx.model.McpServer;
import me.neveux.ccx.model.ProfileDriftState;
import me.neveux.ccx.model.Settings;
import me.neveux.ccx.service.CcxConfigService;
import me.neveux.ccx.service.CcxContextService;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.PathSecurity;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.ProfileService;
import me.neveux.ccx.service.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds a {@link TuiSnapshot} from live disk state after every TUI mutation. Reads
 * project and global {@code settings.json}, the ccx context file, profiles, the
 * CLAUDE.md marker block, MCP server entries, and the plugin map cache — assembling
 * the full immutable data graph that the render pass consumes.
 *
 * <p>This service is package-private so the render thread can call it without
 * triggering a marketplace network fetch; see {@link MarketplaceFetchController}.
 */
@ApplicationScoped
public class TuiDataService {

    private static final Logger log = LoggerFactory.getLogger(TuiDataService.class);
    private static final int MAX_DESC_LEN = 80;
    private static final int MAX_PREVIEW_LEN = 500;

    private final SettingsService settingsService;
    private final ClaudeMdService claudeMdService;
    private final ObjectMapper mapper;
    private final ProfileService profileService;
    private final PluginMapService pluginMapService;
    private final CcxContextService ccxContextService;
    private final CcxConfigService ccxConfigService;

    TuiDataService() {
        this(new SettingsService(), new ClaudeMdService(), ObjectMapperConfig.defaultJson(),
            new CcxContextService(), new ProfileService(), new PluginMapService(),
            new CcxConfigService());
    }

    TuiDataService(SettingsService settingsService, ClaudeMdService claudeMdService, ObjectMapper mapper) {
        this(settingsService, claudeMdService,
            mapper.copy().registerModule(new JavaTimeModule()),
            new CcxContextService(), new ProfileService(), new PluginMapService(),
            new CcxConfigService());
    }

    @Inject
    public TuiDataService(SettingsService settingsService, ClaudeMdService claudeMdService,
                          @Named("jsonMapper") ObjectMapper mapper,
                          CcxContextService ccxContextService,
                          ProfileService profileService,
                          PluginMapService pluginMapService,
                          CcxConfigService ccxConfigService) {
        this.settingsService = settingsService;
        this.claudeMdService = claudeMdService;
        this.mapper = mapper;
        this.profileService = profileService;
        this.pluginMapService = pluginMapService;
        this.ccxContextService = ccxContextService;
        this.ccxConfigService = ccxConfigService;
    }

    public TuiSnapshot snapshot(Path projectDir) {
        var globalRoot = Path.of(System.getProperty("user.home"));
        return snapshot(projectDir, globalRoot);
    }

    public TuiSnapshot snapshot(Path projectDir, Path globalRoot) {
        var settingsFile = projectDir.resolve(".claude/settings.json");
        var settingsValid = true;
        Settings settings;

        if (!Files.exists(settingsFile)) {
            log.debug("settings absent projectDir={}", projectDir);
            settings = new Settings();
        } else {
            try {
                settings = settingsService.readOrThrow(projectDir);
            } catch (IOException e) {
                log.warn("settings.json is malformed or unreadable path={}", settingsFile, e);
                settingsValid = false;
                settings = new Settings();
            }
        }

        var globalSettings = settingsService.read(globalRoot);
        var ccxContext = ccxContextService.read(projectDir);

        var combinedMarketplaceSources = PluginMapService.mergeSources(settings, globalSettings);
        var marketplace = combinedMarketplaceSources.isEmpty()
            ? PluginMapService.PluginMap.EMPTY
            : pluginMapService.buildMapNoFetch(combinedMarketplaceSources);
        var plugins = buildPluginEntries(settings, globalSettings, marketplace.entries(), marketplace.descriptions());
        var claudeMdEntries = buildStackEntries(projectDir, globalRoot);
        var mcps = buildMcpEntries(settings);

        var profiles = buildProfiles(projectDir, settings, ccxContext);

        var overview = buildOverview(projectDir, globalRoot, plugins, profiles, mcps, claudeMdEntries, ccxContext, settingsValid, settings, globalSettings);

        log.debug("snapshot built projectDir={} pluginCount={} stackCount={} settingsValid={}",
            projectDir, plugins.size(), claudeMdEntries.size(), settingsValid);

        return new TuiSnapshot(overview, profiles, plugins, mcps, claudeMdEntries);
    }

    private List<TuiSnapshot.PluginEntry> buildPluginEntries(Settings settings, Settings globalSettings,
            Map<String, String> marketplaceMap, Map<String, String> descriptionsByQualifiedName) {
        var result = new ArrayList<TuiSnapshot.PluginEntry>();
        var projectPlugins = settings.enabledPlugins() != null
            ? settings.enabledPlugins().keySet()
            : Set.<String>of();

        var mktPaths = new HashMap<String, Path>();
        if (settings.extraKnownMarketplaces() != null) {
            settings.extraKnownMarketplaces().stream()
                .filter(src -> src.path() != null)
                .forEach(src -> mktPaths.put(src.key(), Path.of(src.path())));
        }
        if (globalSettings.extraKnownMarketplaces() != null) {
            globalSettings.extraKnownMarketplaces().stream()
                .filter(src -> src.path() != null)
                .forEach(src -> mktPaths.putIfAbsent(src.key(), Path.of(src.path())));
        }

        projectPlugins.forEach(qualifiedName -> {
            result.add(toEntry(qualifiedName, TuiSnapshot.PluginStatus.PROJECT_ENABLED, descriptionsByQualifiedName, mktPaths));
        });

        if (globalSettings.enabledPlugins() != null) {
            globalSettings.enabledPlugins().keySet().forEach(qualifiedName -> {
                result.add(toEntry(qualifiedName, TuiSnapshot.PluginStatus.GLOBALLY_ENABLED, descriptionsByQualifiedName, mktPaths));
            });
        }

        if (!marketplaceMap.isEmpty()) {
            var accounted = new HashSet<String>();
            accounted.addAll(projectPlugins);
            if (globalSettings.enabledPlugins() != null) {
                accounted.addAll(globalSettings.enabledPlugins().keySet());
            }
            marketplaceMap.forEach((_key, qualifiedName) -> {
                if (!accounted.contains(qualifiedName)) {
                    result.add(toEntry(qualifiedName, TuiSnapshot.PluginStatus.AVAILABLE, descriptionsByQualifiedName, mktPaths));
                }
            });
        }

        return result;
    }

    private TuiSnapshot.PluginEntry toEntry(String qualifiedName, TuiSnapshot.PluginStatus status,
                                            Map<String, String> descriptions, Map<String, Path> mktPaths) {
        var parts = qualifiedName.split("@", 2);
        var shortName = parts[0];
        var marketplace = parts.length > 1 ? parts[1] : "";
        var desc = descriptions.getOrDefault(qualifiedName, "");
        if (desc.isBlank()) {
            var mktRoot = mktPaths.get(marketplace);
            if (mktRoot != null) desc = readPluginDescription(mktRoot, shortName);
        }
        return new TuiSnapshot.PluginEntry(qualifiedName, shortName, marketplace, status, desc);
    }

    /**
     * First line of documentation for {@code shortName} inside {@code marketplaceRoot}, or the empty
     * string when there is none to read.
     *
     * <p>Both arguments come straight out of {@code settings.json} — the root from
     * {@code extraKnownMarketplaces}, the short name from an {@code enabledPlugins} key — so both
     * are validated the way {@link me.neveux.ccx.service.PluginMapService} and
     * {@link me.neveux.ccx.service.SkillsService} validate the same values: an absolute or
     * {@code ..}-bearing short name would escape the root under {@link Path#resolve}, and a root
     * outside the allowed directories must not be read at all.
     */
    private String readPluginDescription(Path marketplaceRoot, String shortName) {
        if (shortName.isBlank() || shortName.contains("/") || shortName.contains("\\")
            || shortName.contains("..") || shortName.indexOf('\0') >= 0) {
            log.debug("skipping plugin description for unusable short name shortName={}", shortName);
            return "";
        }
        if (!PathSecurity.resolveAndCheck(marketplaceRoot)) {
            log.debug("skipping plugin description: marketplace root outside allowed directories path={}",
                marketplaceRoot);
            return "";
        }
        Path root;
        try {
            root = marketplaceRoot.toRealPath();
        } catch (IOException e) {
            log.debug("marketplace root canonicalization failed path={}", marketplaceRoot, e);
            return "";
        }
        var pluginDir = root.resolve(shortName);
        var pluginJsonPath = pluginDir.resolve(".claude-plugin/plugin.json");
        if (Files.exists(pluginJsonPath)) {
            try {
                var node = mapper.readTree(pluginJsonPath.toFile());
                var desc = node.path("description").asText(null);
                if (desc != null && !desc.isBlank()) return desc.length() > MAX_DESC_LEN ? desc.substring(0, MAX_DESC_LEN) : desc;
            } catch (IOException e) {
                log.debug("failed to read plugin.json path={}", pluginJsonPath, e);
            }
        }
        var readmePath = pluginDir.resolve("README.md");
        if (Files.exists(readmePath)) {
            try {
                var lines = Files.readAllLines(readmePath);
                for (var line : lines) {
                    var trimmed = line.trim();
                    if (trimmed.isBlank() || trimmed.startsWith("#")) continue;
                    var end = trimmed.indexOf('.');
                    if (end > 0 && end < MAX_DESC_LEN) return trimmed.substring(0, end + 1);
                    return trimmed.length() > MAX_DESC_LEN ? trimmed.substring(0, MAX_DESC_LEN) : trimmed;
                }
            } catch (IOException e) {
                log.debug("failed to read README path={}", readmePath, e);
            }
        }
        return "";
    }

    private List<TuiSnapshot.StackEntry> buildStackEntries(Path projectDir, Path globalRoot) {
        var claudeMd = projectDir.resolve(".claude/CLAUDE.md");
        var lines = claudeMdService.readMarkerBlock(claudeMd);
        var result = new ArrayList<TuiSnapshot.StackEntry>();

        for (var line : lines) {
            if (!line.startsWith("@")) {
                result.add(new TuiSnapshot.StackEntry(line, extractName(line),
                    TuiSnapshot.StackSource.FRAGMENT, null));
                continue;
            }

            var rawPath = line.substring(1); // strip @
            var name = extractName(rawPath);

            // Resolve path (absolute or tilde-prefixed)
            var resolvedPath = rawPath.startsWith("~/")
                ? Path.of(System.getProperty("user.home"), rawPath.substring(2))
                : Path.of(rawPath);

            if (Files.exists(resolvedPath)) {
                var preview = readPreview(resolvedPath);
                result.add(new TuiSnapshot.StackEntry(line, name, TuiSnapshot.StackSource.PROJECT, preview));
            } else {
                result.add(new TuiSnapshot.StackEntry(line, name, TuiSnapshot.StackSource.MISSING,
                    PreviewMarkdownBuilder.missingStackPreview(line)));
            }
        }

        // Discover available (not-yet-referenced) fragments from ~/.config/ccx/claudemd/
        var alreadyReferenced = result.stream()
            .filter(e -> e.source() != TuiSnapshot.StackSource.AVAILABLE)
            .map(TuiSnapshot.StackEntry::line)
            .collect(Collectors.toSet());

        discoverAvailableFragments(ccxConfigService.globalClaudeMdDir(), alreadyReferenced, result, "global");

        // Discover available fragments from each known profile's claudemd/ dir
        for (var profileName : profileService.listAvailableProfiles()) {
            discoverAvailableFragments(ccxConfigService.profileClaudeMdDir(profileName),
                alreadyReferenced, result, "profile=" + profileName);
        }

        result.sort(Comparator.comparing(TuiSnapshot.StackEntry::name));
        return result;
    }

    private void discoverAvailableFragments(Path dir, Set<String> alreadyReferenced,
            List<TuiSnapshot.StackEntry> out, String logContext) {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(f -> f.toString().endsWith(".md") && Files.isRegularFile(f))
                .forEach(stackFile -> {
                    var availLine = "@" + stackFile.toAbsolutePath().normalize();
                    if (!alreadyReferenced.contains(availLine)) {
                        out.add(new TuiSnapshot.StackEntry(availLine, extractName(stackFile.toString()),
                            TuiSnapshot.StackSource.AVAILABLE, readPreview(stackFile)));
                    }
                });
        } catch (IOException e) {
            log.warn("failed to list claudemd dir context={} path={}", logContext, dir, e);
        }
    }

    private String extractName(String path) {
        // Extract filename without extension from a path or line
        var filename = Path.of(path).getFileName();
        if (filename == null) return path;
        var s = filename.toString();
        if (s.endsWith(".md")) {
            return s.substring(0, s.length() - 3);
        }
        return s;
    }

    private String readPreview(Path file) {
        try {
            var content = Files.readString(file);
            return content.length() > MAX_PREVIEW_LEN ? content.substring(0, MAX_PREVIEW_LEN) : content;
        } catch (IOException e) {
            log.debug("failed to read preview path={}", file, e);
            return null;
        }
    }

    private List<TuiSnapshot.McpEntry> buildMcpEntries(Settings settings) {
        if (settings.mcpServers() == null || settings.mcpServers().isEmpty()) return List.of();
        var result = new ArrayList<TuiSnapshot.McpEntry>();
        settings.mcpServers().forEach((name, config) ->
            result.add(new TuiSnapshot.McpEntry(name, config)));
        return result;
    }

    private List<TuiSnapshot.ProfileEntry> buildProfiles(Path projectDir, Settings settings, CcxContext ccxContext) {
        var result = new ArrayList<TuiSnapshot.ProfileEntry>();
        var claudeDir = projectDir.resolve(".claude");
        var markerLines = claudeMdService.readMarkerBlock(projectDir.resolve(".claude/CLAUDE.md"));

        for (var name : profileService.listAvailableProfiles()) {
            try {
                var p = profileService.load(name);
                var description = (p.metadata() != null && p.metadata().description() != null)
                    ? p.metadata().description() : "";

                ProfileDriftState driftState;
                if (ccxContext.enabledProfiles().containsKey(name)) {
                    if (!profileService.missingDependencies(name, ccxContext.enabledProfiles().keySet()).isEmpty()) {
                        driftState = ProfileDriftState.BROKEN;
                    } else {
                        var record = ccxContext.enabledProfiles().get(name);
                        driftState = ccxContextService.detectDrift(name, record.snapshot(), settings, claudeDir, markerLines);
                    }
                } else {
                    driftState = ProfileDriftState.DISABLED;
                }

                var previewMarkdown = PreviewMarkdownBuilder.profilePreview(name, p, driftState, ccxConfigService.profileDir(name));
                result.add(new TuiSnapshot.ProfileEntry(name, description, driftState, previewMarkdown));
            } catch (Exception e) {
                log.warn("failed to load profile for TUI name={}", name, e);
                result.add(new TuiSnapshot.ProfileEntry(name, "", ProfileDriftState.DISABLED,
                    "## " + name + "\n\n_Error: could not load profile.yaml (" + e.getClass().getSimpleName() + ")_\n"));
            }
        }
        return result;
    }

    private TuiSnapshot.OverviewData buildOverview(
        Path projectDir, Path globalRoot,
        List<TuiSnapshot.PluginEntry> plugins,
        List<TuiSnapshot.ProfileEntry> profiles,
        List<TuiSnapshot.McpEntry> mcps,
        List<TuiSnapshot.StackEntry> claudeMdEntries,
        CcxContext ccxContext,
        boolean settingsValid,
        Settings settings,
        Settings globalSettings
    ) {
        var enabledProfileNames = profiles.stream()
            .filter(p -> p.driftState() != ProfileDriftState.DISABLED)
            .map(TuiSnapshot.ProfileEntry::name)
            .toList();

        var profileDriftStates = new LinkedHashMap<String, ProfileDriftState>();
        profiles.forEach(p -> profileDriftStates.put(p.name(), p.driftState()));

        var projectPluginCount = (int) plugins.stream()
            .filter(p -> p.status() == TuiSnapshot.PluginStatus.PROJECT_ENABLED)
            .count();
        var globalPluginCount = (int) plugins.stream()
            .filter(p -> p.status() == TuiSnapshot.PluginStatus.GLOBALLY_ENABLED)
            .count();

        var projectClaudeMd = projectDir.resolve("CLAUDE.md");
        var globalClaudeMd = globalRoot.resolve(".claude/CLAUDE.md");

        var contextFilePathRaw = projectDir.resolve(".ccx/context.json");
        var contextFileExists = Files.exists(contextFilePathRaw);

        var rulesDir = projectDir.resolve(".claude/rules");
        var rulesExists = Files.isDirectory(rulesDir);

        var pluginNames = buildPluginNameLists(plugins);
        var rulesFileNames = rulesExists ? listFileNames(rulesDir) : List.<String>of();
        var contextFilePath = contextFileExists
            ? contextFilePathRaw.toAbsolutePath().toString() : null;
        var projectClaudeMdPath = Files.exists(projectClaudeMd)
            ? projectClaudeMd.toAbsolutePath().toString() : null;
        var globalClaudeMdPath = Files.exists(globalClaudeMd)
            ? globalClaudeMd.toAbsolutePath().toString() : null;
        var globalHookCount = globalSettings.hooks() != null
            ? globalSettings.hooks().size() : 0;

        var privateClaudeMdFile = projectDir.resolve(".claude/CLAUDE.md");
        var privateClaudeMdPath = Files.exists(privateClaudeMdFile)
            ? privateClaudeMdFile.toAbsolutePath().toString() : null;

        var fragmentNames = claudeMdEntries.stream()
            .filter(e -> e.source() == TuiSnapshot.StackSource.PROJECT
                || e.source() == TuiSnapshot.StackSource.FRAGMENT)
            .map(TuiSnapshot.StackEntry::name)
            .distinct()
            .sorted()
            .toList();
        var mcpServerNames = settings.mcpServers() == null
            ? List.<String>of()
            : settings.mcpServers().keySet().stream().sorted().toList();

        return new TuiSnapshot.OverviewData.Builder()
            .enabledProfileNames(enabledProfileNames)
            .profileDriftStates(profileDriftStates)
            .projectPluginCount(projectPluginCount)
            .globalPluginCount(globalPluginCount)
            .mcpCount(mcps.size())
            .hookCount(settings.hooks() != null ? settings.hooks().size() : 0)
            .projectPluginNames(pluginNames.projectNames())
            .globalPluginNames(pluginNames.globalNames())
            .rulesFileNames(rulesFileNames)
            .contextFilePath(contextFilePath)
            .projectClaudeMdPath(projectClaudeMdPath)
            .globalClaudeMdPath(globalClaudeMdPath)
            .globalHookCount(globalHookCount)
            .privateClaudeMdPath(privateClaudeMdPath)
            .fragmentNames(fragmentNames)
            .mcpServerNames(mcpServerNames)
            .settingsValid(settingsValid)
            .build();
    }

    private record PluginNameLists(List<String> projectNames, List<String> globalNames) {}

    private PluginNameLists buildPluginNameLists(List<TuiSnapshot.PluginEntry> plugins) {
        var projectNames = plugins.stream()
            .filter(p -> p.status() == TuiSnapshot.PluginStatus.PROJECT_ENABLED)
            .map(TuiSnapshot.PluginEntry::qualifiedName)
            .sorted()
            .toList();
        var globalNames = plugins.stream()
            .filter(p -> p.status() == TuiSnapshot.PluginStatus.GLOBALLY_ENABLED)
            .map(TuiSnapshot.PluginEntry::qualifiedName)
            .sorted()
            .toList();
        return new PluginNameLists(projectNames, globalNames);
    }

    private List<String> listFileNames(Path dir) {
        try (var s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .sorted()
                .toList();
        } catch (IOException e) {
            log.warn("failed to list dir path={}", dir, e);
            return List.of();
        }
    }

}
