package me.neveux.ccx.service;

import me.neveux.ccx.model.CcxContext;
import me.neveux.ccx.model.Permissions;
import me.neveux.ccx.model.Profile;
import me.neveux.ccx.model.Settings;
import me.neveux.ccx.util.FileUtils;
import me.neveux.ccx.util.ListUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Applies a resolved {@link me.neveux.ccx.model.Profile} to a project directory, and
 * conversely removes one when a profile is disabled.
 *
 * <p>On {@link #apply}, the service:
 * <ol>
 *   <li>Enables each plugin in {@code .claude/settings.json} (resolving short names via
 *       {@link PluginMapService}).</li>
 *   <li>Merges {@code allow}/{@code deny} permission lists into {@code settings.json}.</li>
 *   <li>Copies rule markdown files from the profile directory into {@code .claude/rules/}.</li>
 *   <li>Appends {@code @absPath} fragment references into the ccx block in
 *       {@code .claude/CLAUDE.md}.</li>
 *   <li>Copies command markdown files into {@code .claude/commands/}.</li>
 *   <li>Merges {@code .claudeignore} patterns, bootstrapping the file if absent.</li>
 *   <li>Merges environment variable entries into {@code settings.json}.</li>
 * </ol>
 *
 * <p>On {@link #remove}, every resource recorded in the profile snapshot is undone,
 * skipping any resource that is still claimed by another active sibling profile.
 *
 * <p>All writes are atomic via {@link me.neveux.ccx.util.FileUtils#atomicWrite}.
 */
@ApplicationScoped
public class ProfileApplyService {

    private static final Logger log = LoggerFactory.getLogger(ProfileApplyService.class);

    /** Default .claudeignore bootstrapped on first profile enable (migrated from the removed `init` command). */
    static final String DEFAULT_CLAUDEIGNORE = ".git/\ntarget/\nnode_modules/\n*.class\n";

    private final SettingsService settingsService;
    private final ClaudeMdService claudeMdService;
    private final PluginMapService pluginMapService;
    private final CcxConfigService ccxConfigService;

    public ProfileApplyService() {
        this.settingsService = new SettingsService();
        this.claudeMdService = new ClaudeMdService();
        this.pluginMapService = new PluginMapService();
        this.ccxConfigService = new CcxConfigService();
    }

    @Inject
    public ProfileApplyService(SettingsService settingsService, ClaudeMdService claudeMdService,
                               PluginMapService pluginMapService, CcxConfigService ccxConfigService) {
        this.settingsService = settingsService;
        this.claudeMdService = claudeMdService;
        this.pluginMapService = pluginMapService;
        this.ccxConfigService = ccxConfigService;
    }

    /**
     * Applies the resolved profile to the project, overwriting existing files where
     * conflicts arise. Equivalent to {@link #apply(Profile, Path, boolean) apply(resolved, projectRoot, true)}.
     *
     * @param resolved    the fully merged profile to apply
     * @param projectRoot root of the project to configure
     * @return a snapshot of all resources written, for recording in the context file
     */
    public CcxContext.ProfileSnapshot apply(Profile resolved, Path projectRoot) {
        return apply(resolved, projectRoot, true);
    }

    /**
     * Applies the resolved profile to the project.
     *
     * @param resolved    the fully merged profile to apply
     * @param projectRoot root of the project to configure
     * @param overwrite   when {@code true}, existing rule and command files are replaced;
     *                    when {@code false}, they are kept and still included in the snapshot
     * @return a snapshot of all resources written, for recording in the context file
     */
    public CcxContext.ProfileSnapshot apply(Profile resolved, Path projectRoot, boolean overwrite) {
        var profileName = resolved.metadata() != null ? resolved.metadata().name() : null;
        var s = settingsService.read(projectRoot);

        var writtenPlugins = new ArrayList<String>();
        s = applyPluginsAndPermissions(s, resolved, projectRoot, writtenPlugins);

        var writtenEnv = new LinkedHashMap<String, String>();
        s = applyEnv(s, resolved, writtenEnv);
        settingsService.write(s, projectRoot);

        var writtenRules = applyRules(resolved, projectRoot, overwrite, profileName);
        var writtenFragments = applyFragments(resolved, projectRoot, profileName);
        var writtenCommands = applyCommands(resolved, projectRoot, overwrite, profileName);
        var writtenIgnorePatterns = applyClaudeIgnore(resolved, projectRoot);

        var permissionsAllow = resolved.permissions() != null && resolved.permissions().allow() != null
            ? List.copyOf(resolved.permissions().allow())
            : List.<String>of();
        var permissionsDeny = resolved.permissions() != null && resolved.permissions().deny() != null
            ? List.copyOf(resolved.permissions().deny())
            : List.<String>of();

        log.info("profile applied plugins={} rules={} commands={} fragments={} env={} ignore={}",
            writtenPlugins, writtenRules, writtenCommands, writtenFragments,
            writtenEnv.keySet(), writtenIgnorePatterns);
        return new CcxContext.ProfileSnapshot(
            writtenPlugins.isEmpty() ? List.of() : List.copyOf(writtenPlugins),
            writtenRules.isEmpty() ? List.of() : List.copyOf(writtenRules),
            permissionsAllow, permissionsDeny,
            writtenCommands.isEmpty() ? List.of() : List.copyOf(writtenCommands),
            writtenFragments.isEmpty() ? List.of() : List.copyOf(writtenFragments),
            writtenEnv.isEmpty() ? null : Map.copyOf(writtenEnv),
            writtenIgnorePatterns.isEmpty() ? List.of() : List.copyOf(writtenIgnorePatterns));
    }

    private Settings applyPluginsAndPermissions(Settings s, Profile resolved, Path projectRoot,
                                                List<String> writtenPlugins) {
        var enabledPlugins = s.enabledPlugins() == null
            ? new LinkedHashMap<String, Boolean>()
            : new LinkedHashMap<>(s.enabledPlugins());

        if (resolved.plugins() != null && resolved.plugins().enable() != null) {
            for (var shortName : resolved.plugins().enable()) {
                var qualified = pluginMapService.resolve(shortName, projectRoot, s)
                    .orElse(shortName);
                enabledPlugins.put(qualified, true);
                writtenPlugins.add(qualified);
            }
        }
        s = s.withEnabledPlugins(enabledPlugins);

        if (resolved.permissions() != null) {
            s = s.withPermissions(mergePermissionsList(s.permissions(), resolved.permissions()));
        }
        return s;
    }

    private Settings applyEnv(Settings s, Profile resolved, Map<String, String> writtenEnv) {
        if (resolved.env() != null && !resolved.env().isEmpty()) {
            var env = s.env() == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<>(s.env());
            env.putAll(resolved.env());
            writtenEnv.putAll(resolved.env());
            s = s.withEnv(env);
        }
        return s;
    }

    private List<String> applyRules(Profile resolved, Path projectRoot, boolean overwrite, String profileName) {
        var writtenRules = new ArrayList<String>();
        if (resolved.rules() != null && resolved.rules().files() != null) {
            var rulesDir = projectRoot.resolve(".claude/rules").normalize();
            var profileRulesDir = profileName != null
                ? ccxConfigService.profileDir(profileName).resolve("rules")
                : null;
            for (var ruleName : resolved.rules().files()) {
                if (ruleName == null) continue;
                var targetPath = rulesDir.resolve(ruleName + ".md").normalize();
                if (!targetPath.startsWith(rulesDir)) {
                    throw new IllegalArgumentException("invalid rule name: " + ruleName);
                }
                if (profileRulesDir == null) {
                    log.warn("no profile name — cannot locate rule source for rule={}", ruleName);
                    continue;
                }
                var sourcePath = profileRulesDir.resolve(ruleName + ".md");
                if (!Files.exists(sourcePath)) {
                    log.warn("rule file not found in profile dir rule={} source={}", ruleName, sourcePath);
                    continue;
                }
                if (Files.exists(targetPath) && !overwrite) {
                    log.debug("rule file already exists, skipping path={}", targetPath);
                    writtenRules.add(ruleName);
                    continue;
                }
                try {
                    var content = Files.readString(sourcePath);
                    FileUtils.atomicWrite(targetPath, tmp -> Files.writeString(tmp, content));
                    writtenRules.add(ruleName);
                    log.debug("rule file written path={}", targetPath);
                } catch (IOException e) {
                    log.warn("failed to write rule file name={}", ruleName, e);
                }
            }
        }
        return writtenRules;
    }

    private List<String> applyFragments(Profile resolved, Path projectRoot, String profileName) {
        var claudeMd = projectRoot.resolve(".claude/CLAUDE.md");
        var existingLines = new ArrayList<>(claudeMdService.readMarkerBlock(claudeMd));
        var writtenFragments = new ArrayList<String>();
        if (profileName != null) {
            var fragmentsDir = ccxConfigService.profileClaudeMdDir(profileName);
            if (Files.isDirectory(fragmentsDir)) {
                try (var fragStream = Files.list(fragmentsDir)) {
                    fragStream
                        .filter(p -> p.getFileName().toString().endsWith(".md"))
                        .sorted()
                        .forEach(fragPath -> {
                            if (!isUnderConfigDir(fragPath)) {
                                return;
                            }
                            var absPath = fragPath.toAbsolutePath().normalize().toString();
                            var fragLine = "@" + absPath;
                            if (!existingLines.contains(fragLine)) {
                                existingLines.add(fragLine);
                            }
                            writtenFragments.add(absPath);
                        });
                } catch (IOException e) {
                    log.warn("failed to scan fragments dir profile={}", profileName, e);
                }
            }
        }
        claudeMdService.writeMarkerBlock(claudeMd, existingLines);
        return writtenFragments;
    }

    private List<String> applyCommands(Profile resolved, Path projectRoot, boolean overwrite, String profileName) {
        var writtenCommands = new ArrayList<String>();
        if (profileName != null) {
            var commandsSourceDir = ccxConfigService.profileDir(profileName).resolve("commands");
            if (Files.isDirectory(commandsSourceDir)) {
                var commandsTargetDir = projectRoot.resolve(".claude/commands").normalize();
                try (var cmdStream = Files.list(commandsSourceDir)) {
                    cmdStream
                        .filter(p -> p.getFileName().toString().endsWith(".md"))
                        .forEach(cmdPath -> {
                            if (!isUnderConfigDir(cmdPath)) {
                                return;
                            }
                            var basename = cmdPath.getFileName().toString();
                            var targetPath = commandsTargetDir.resolve(basename).normalize();
                            if (!targetPath.startsWith(commandsTargetDir)) {
                                log.warn("skipping command file with traversal name: {}", basename);
                                return;
                            }
                            var cmdName = basename.replaceAll("\\.md$", "");
                            if (Files.exists(targetPath) && !overwrite) {
                                log.debug("command file already exists, skipping path={}", targetPath);
                                writtenCommands.add(cmdName);
                                return;
                            }
                            try {
                                var content = Files.readString(cmdPath);
                                FileUtils.atomicWrite(targetPath, tmp -> Files.writeString(tmp, content));
                                writtenCommands.add(cmdName);
                                log.debug("command file written path={}", targetPath);
                            } catch (IOException e) {
                                log.warn("failed to write command file name={}", basename, e);
                            }
                        });
                } catch (IOException e) {
                    log.warn("failed to scan commands dir profile={}", profileName, e);
                }
            }
        }
        return writtenCommands;
    }

    private List<String> applyClaudeIgnore(Profile resolved, Path projectRoot) {
        bootstrapClaudeIgnore(projectRoot);

        var writtenIgnorePatterns = new ArrayList<String>();
        if (resolved.ignore() != null && !resolved.ignore().isEmpty()) {
            var claudeIgnore = projectRoot.resolve(".claudeignore");
            List<String> existing = new ArrayList<>();
            if (Files.exists(claudeIgnore)) {
                try {
                    existing = new ArrayList<>(Files.readAllLines(claudeIgnore));
                } catch (IOException e) {
                    log.warn("failed to read .claudeignore path={}", claudeIgnore, e);
                }
            }
            for (var pattern : resolved.ignore()) {
                if (pattern != null && !existing.contains(pattern)) {
                    existing.add(pattern);
                    writtenIgnorePatterns.add(pattern);
                }
            }
            if (!writtenIgnorePatterns.isEmpty()) {
                try {
                    var content = String.join("\n", existing) + "\n";
                    FileUtils.atomicWrite(claudeIgnore, tmp -> Files.writeString(tmp, content));
                    log.debug("ignore patterns written count={}", writtenIgnorePatterns.size());
                } catch (IOException e) {
                    log.warn("failed to write .claudeignore path={}", claudeIgnore, e);
                }
            }
        }
        return writtenIgnorePatterns;
    }

    /**
     * Safe overload: skips removing any resource that is still claimed by a sibling
     * active profile in {@code context} (i.e. any profile other than {@code profileName}).
     */
    public void remove(CcxContext.ProfileSnapshot snapshot, Path projectRoot,
                       CcxContext context, String profileName) {
        var siblingPlugins = new HashSet<String>();
        var siblingRules = new HashSet<String>();
        var siblingFragments = new HashSet<String>();
        var siblingCommands = new HashSet<String>();
        var siblingEnvKeys = new HashSet<String>();
        var siblingIgnorePatterns = new HashSet<String>();

        for (var entry : context.enabledProfiles().entrySet()) {
            if (entry.getKey().equals(profileName)) continue;
            var s = entry.getValue().snapshot();
            if (s.plugins() != null) siblingPlugins.addAll(s.plugins());
            if (s.rules() != null) siblingRules.addAll(s.rules());
            if (s.fragments() != null) siblingFragments.addAll(s.fragments());
            if (s.commands() != null) siblingCommands.addAll(s.commands());
            if (s.env() != null) siblingEnvKeys.addAll(s.env().keySet());
            if (s.ignorePatterns() != null) siblingIgnorePatterns.addAll(s.ignorePatterns());
        }

        var filteredPlugins = snapshot.plugins() == null ? List.<String>of()
            : snapshot.plugins().stream().filter(p -> !siblingPlugins.contains(p)).toList();
        var filteredRules = snapshot.rules() == null ? List.<String>of()
            : snapshot.rules().stream().filter(r -> !siblingRules.contains(r)).toList();
        var filteredFragments = snapshot.fragments() == null ? List.<String>of()
            : snapshot.fragments().stream().filter(f -> !siblingFragments.contains(f)).toList();
        var filteredCommands = snapshot.commands() == null ? List.<String>of()
            : snapshot.commands().stream().filter(c -> !siblingCommands.contains(c)).toList();
        Map<String, String> filteredEnv = snapshot.env() == null ? null
            : snapshot.env().entrySet().stream()
                .filter(e -> !siblingEnvKeys.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        var filteredIgnorePatterns = snapshot.ignorePatterns() == null ? List.<String>of()
            : snapshot.ignorePatterns().stream().filter(p -> !siblingIgnorePatterns.contains(p)).toList();

        var filteredSnapshot = new CcxContext.ProfileSnapshot(
            filteredPlugins,
            filteredRules,
            snapshot.permissionsAllow(),
            snapshot.permissionsDeny(),
            filteredCommands,
            filteredFragments,
            filteredEnv,
            filteredIgnorePatterns
        );
        remove(filteredSnapshot, projectRoot);
    }

    /**
     * Removes all resources recorded in {@code snapshot} from the project, without checking
     * whether other active profiles still claim those resources. Prefer
     * {@link #remove(CcxContext.ProfileSnapshot, Path, CcxContext, String)} when other
     * profiles may be active.
     *
     * @param snapshot    the snapshot previously returned by {@link #apply}
     * @param projectRoot root of the project to clean up
     */
    public void remove(CcxContext.ProfileSnapshot snapshot, Path projectRoot) {
        // Remove plugins from settings
        var settings = settingsService.read(projectRoot);
        if (settings.enabledPlugins() != null && !snapshot.plugins().isEmpty()) {
            var updated = new LinkedHashMap<>(settings.enabledPlugins());
            snapshot.plugins().forEach(updated::remove);
            settingsService.write(settings.withEnabledPlugins(updated), projectRoot);
        }

        // Remove rule files
        var rulesDir = projectRoot.resolve(".claude/rules").normalize();
        for (var rule : snapshot.rules()) {
            var ruleFile = rulesDir.resolve(rule + ".md").normalize();
            if (!ruleFile.startsWith(rulesDir)) {
                log.warn("Skipping removal of rule outside rules dir: {}", rule);
                continue;
            }
            try {
                Files.deleteIfExists(ruleFile);
                log.debug("rule file removed path={}", ruleFile);
            } catch (IOException e) {
                log.warn("failed to remove rule file path={}", ruleFile, e);
            }
        }

        // Remove command files
        var commandsDir = projectRoot.resolve(".claude/commands").normalize();
        for (var cmd : snapshot.commands() == null ? List.<String>of() : snapshot.commands()) {
            var cmdFile = commandsDir.resolve(cmd + ".md").normalize();
            if (!cmdFile.startsWith(commandsDir)) {
                log.warn("Skipping removal of command outside commands dir: {}", cmd);
                continue;
            }
            try {
                Files.deleteIfExists(cmdFile);
                log.debug("command file removed path={}", cmdFile);
            } catch (IOException e) {
                log.warn("failed to remove command file path={}", cmdFile, e);
            }
        }

        // Remove fragment @path entries from CLAUDE.md
        if (snapshot.fragments() != null && !snapshot.fragments().isEmpty()) {
            var claudeMd = projectRoot.resolve(".claude/CLAUDE.md");
            var currentLines = new ArrayList<>(claudeMdService.readMarkerBlock(claudeMd));
            snapshot.fragments().forEach(absPath -> currentLines.remove("@" + absPath));
            claudeMdService.writeMarkerBlock(claudeMd, currentLines);
        }

        // Remove env vars written by this profile
        if (snapshot.env() != null && !snapshot.env().isEmpty()) {
            var settingsForEnv = settingsService.read(projectRoot);
            if (settingsForEnv.env() != null) {
                var updatedEnv = new LinkedHashMap<>(settingsForEnv.env());
                snapshot.env().keySet().forEach(updatedEnv::remove);
                settingsService.write(
                    settingsForEnv.withEnv(updatedEnv.isEmpty() ? null : updatedEnv), projectRoot);
            }
        }

        // Remove ignore patterns
        if (snapshot.ignorePatterns() != null && !snapshot.ignorePatterns().isEmpty()) {
            var claudeIgnore = projectRoot.resolve(".claudeignore");
            if (Files.exists(claudeIgnore)) {
                try {
                    var lines = new ArrayList<>(Files.readAllLines(claudeIgnore));
                    lines.removeAll(snapshot.ignorePatterns());
                    var content = String.join("\n", lines) + "\n";
                    FileUtils.atomicWrite(claudeIgnore, tmp -> Files.writeString(tmp, content));
                    log.debug("ignore patterns removed count={}", snapshot.ignorePatterns().size());
                } catch (IOException e) {
                    log.warn("failed to update .claudeignore on remove path={}", claudeIgnore, e);
                }
            }
        }

        log.info("profile removed plugins={} rules={}", snapshot.plugins(), snapshot.rules());
    }

    private boolean isUnderConfigDir(Path file) {
        try {
            return file.toRealPath().startsWith(ccxConfigService.configDir().toRealPath());
        } catch (IOException e) {
            log.warn("could not resolve source path path={}", file, e);
            return false;
        }
    }

    private void bootstrapClaudeIgnore(Path projectRoot) {
        var claudeIgnore = projectRoot.resolve(".claudeignore");
        if (Files.exists(claudeIgnore)) {
            log.debug(".claudeignore already exists, skipping bootstrap path={}", claudeIgnore);
            return;
        }
        try {
            FileUtils.atomicWrite(claudeIgnore, tmp -> Files.writeString(tmp, DEFAULT_CLAUDEIGNORE));
            log.debug(".claudeignore bootstrapped path={}", claudeIgnore);
        } catch (IOException e) {
            log.warn("failed to bootstrap .claudeignore path={}", claudeIgnore, e);
        }
    }

    private Permissions mergePermissionsList(Permissions target, Permissions source) {
        var targetAllow = target != null ? target.allow() : null;
        var targetDeny = target != null ? target.deny() : null;
        var allow = source.allow() == null ? targetAllow : ListUtils.orderedUnion(targetAllow, source.allow());
        var deny = source.deny() == null ? targetDeny : ListUtils.orderedUnion(targetDeny, source.deny());
        return new Permissions(allow, deny);
    }
}
