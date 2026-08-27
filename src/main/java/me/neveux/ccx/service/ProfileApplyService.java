package me.neveux.ccx.service;

import me.neveux.ccx.model.CcxContext;
import me.neveux.ccx.model.Permissions;
import me.neveux.ccx.model.Profile;
import me.neveux.ccx.model.Settings;
import me.neveux.ccx.util.FileUtils;
import me.neveux.ccx.util.IgnorePatterns;
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
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Applies a resolved {@link me.neveux.ccx.model.Profile} to a project directory, and
 * conversely removes one when a profile is disabled.
 *
 * <p>On {@link #apply}, the service performs the {@code settings.json} steps first, in this
 * order, threading one {@link Settings} value through them and writing it once:
 * <ol>
 *   <li>Enables each plugin in {@code .claude/settings.json} (resolving short names via
 *       {@link PluginMapService}).</li>
 *   <li>Merges {@code allow}/{@code deny} permission lists.</li>
 *   <li>Merges environment variable entries.</li>
 *   <li>Translates {@code ignore:} patterns into {@code Read(...)} deny rules and merges them.</li>
 * </ol>
 * <p>Then the filesystem steps, each writing its own files:
 * <ol start="5">
 *   <li>Copies rule markdown files from the profile directory into {@code .claude/rules/}.</li>
 *   <li>Appends {@code @absPath} fragment references into the ccx block in
 *       {@code .claude/CLAUDE.md}.</li>
 *   <li>Copies command markdown files into {@code .claude/commands/}.</li>
 * </ol>
 * <p>A new {@code settings.json} step must be ordered before the single
 * {@link SettingsService#write} call, or it is silently dropped.
 *
 * <p>On {@link #remove}, every resource recorded in the profile snapshot is undone,
 * skipping any resource that is still claimed by another active sibling profile.
 *
 * <p>All writes are atomic via {@link me.neveux.ccx.util.FileUtils#atomicWrite}.
 */
@ApplicationScoped
public class ProfileApplyService {

    private static final Logger log = LoggerFactory.getLogger(ProfileApplyService.class);

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
     * Test-only convenience: applies the resolved profile with no recorded context, overwriting
     * existing files where conflicts arise. Package-private on purpose — production callers must
     * use {@link #apply(Profile, Path, boolean, CcxContext)}, because an empty context makes every
     * already-present deny rule look user-owned and strands it on the next disable.
     *
     * @param resolved    the fully merged profile to apply
     * @param projectRoot root of the project to configure
     * @return a snapshot of all resources written, for recording in the context file
     */
    CcxContext.ProfileSnapshot apply(Profile resolved, Path projectRoot) {
        return apply(resolved, projectRoot, true);
    }

    /**
     * Test-only convenience: applies the resolved profile with no recorded context.
     * Package-private on purpose — see {@link #apply(Profile, Path)}.
     *
     * @param resolved    the fully merged profile to apply
     * @param projectRoot root of the project to configure
     * @param overwrite   when {@code true}, existing rule and command files are replaced;
     *                    when {@code false}, they are kept and still included in the snapshot
     * @return a snapshot of all resources written, for recording in the context file
     */
    CcxContext.ProfileSnapshot apply(Profile resolved, Path projectRoot, boolean overwrite) {
        return apply(resolved, projectRoot, overwrite, CcxContext.empty());
    }

    /**
     * Applies the resolved profile to the project, using {@code context} to tell a
     * {@code permissions.deny} rule some profile already contributed apart from one the user
     * wrote by hand. Prefer this overload whenever the context file is available: without it,
     * a rule this same profile wrote on a previous enable looks user-owned, so it drops out of
     * the snapshot and no later {@code disable} can remove it.
     *
     * @param resolved    the fully merged profile to apply
     * @param projectRoot root of the project to configure
     * @param overwrite   when {@code true}, existing rule and command files are replaced;
     *                    when {@code false}, they are kept and still included in the snapshot
     * @param context     the recorded context, used for deny-rule provenance
     * @return a snapshot of all resources written, for recording in the context file
     */
    public CcxContext.ProfileSnapshot apply(Profile resolved, Path projectRoot, boolean overwrite,
                                            CcxContext context) {
        var profileName = resolved.metadata() != null ? resolved.metadata().name() : null;
        var s = settingsService.readForUpdate(projectRoot);
        var priorPermissions = s.permissions();

        var writtenPlugins = new ArrayList<String>();
        s = applyPluginsAndPermissions(s, resolved, projectRoot, writtenPlugins);

        var writtenEnv = new LinkedHashMap<String, String>();
        s = applyEnv(s, resolved, writtenEnv);

        var writtenIgnorePatterns = new ArrayList<String>();
        s = applyIgnoreAsDenyRules(s, resolved, writtenIgnorePatterns, ccxOwnedDenyRules(context));
        settingsService.write(s, projectRoot);

        var writtenRules = applyRules(projectRoot, overwrite, profileName);
        var writtenFragments = applyFragments(projectRoot, profileName);
        var writtenCommands = applyCommands(projectRoot, overwrite, profileName);

        var permissionsAllow = claimedPermissions(
            resolved.permissions() != null ? resolved.permissions().allow() : null,
            priorPermissions != null ? priorPermissions.allow() : null,
            ccxClaimedPermissions(context, CcxContext.ProfileSnapshot::permissionsAllow));
        var permissionsDeny = claimedPermissions(
            resolved.permissions() != null ? resolved.permissions().deny() : null,
            priorPermissions != null ? priorPermissions.deny() : null,
            ccxClaimedPermissions(context, CcxContext.ProfileSnapshot::permissionsDeny));

        log.info("profile applied plugins={} rules={} commands={} fragments={} env={}",
            writtenPlugins, writtenRules, writtenCommands, writtenFragments,
            writtenEnv.keySet());
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

    private List<String> applyRules(Path projectRoot, boolean overwrite, String profileName) {
        var writtenRules = new ArrayList<String>();
        if (profileName != null) {
            var rulesSourceDir = ccxConfigService.profileDir(profileName).resolve("rules");
            if (Files.isDirectory(rulesSourceDir)) {
                var rulesTargetDir = projectRoot.resolve(".claude/rules").normalize();
                try (var ruleStream = Files.list(rulesSourceDir)) {
                    ruleStream
                        .filter(p -> p.getFileName().toString().endsWith(".md"))
                        .sorted()
                        .forEach(rulePath -> {
                            if (!isUnderConfigDir(rulePath)) {
                                return;
                            }
                            var basename = rulePath.getFileName().toString();
                            var targetPath = rulesTargetDir.resolve(basename).normalize();
                            if (!targetPath.startsWith(rulesTargetDir)) {
                                log.warn("skipping rule file with traversal name: {}", basename);
                                return;
                            }
                            var ruleName = basename.replaceAll("\\.md$", "");
                            if (Files.exists(targetPath) && !overwrite) {
                                log.debug("rule file already exists, skipping path={}", targetPath);
                                writtenRules.add(ruleName);
                                return;
                            }
                            try {
                                var content = Files.readString(rulePath);
                                FileUtils.atomicWrite(targetPath, tmp -> Files.writeString(tmp, content));
                                writtenRules.add(ruleName);
                                log.debug("rule file written path={}", targetPath);
                            } catch (IOException e) {
                                log.warn("failed to write rule file name={}", basename, e);
                            }
                        });
                } catch (IOException e) {
                    log.warn("failed to scan rules dir profile={}", profileName, e);
                }
            }
        }
        return writtenRules;
    }

    private List<String> applyFragments(Path projectRoot, String profileName) {
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

    private List<String> applyCommands(Path projectRoot, boolean overwrite, String profileName) {
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

    /**
     * Translates the profile's {@code ignore:} patterns into {@code Read(...)} deny rules and
     * merges them into the {@code permissions.deny} list of {@code settings}.
     *
     * @param writtenIgnorePatterns collects the stripped patterns this profile claims, so
     *                              {@link #remove} never undoes a deny rule the user wrote by hand
     * @param ccxOwnedDenyRules     deny rules some recorded profile already contributed; a rule in
     *                              this set is claimed even though it is already present
     * @return {@code settings} with the merged deny rules, or unchanged when there is nothing to add
     */
    private Settings applyIgnoreAsDenyRules(Settings settings, Profile resolved,
                                            List<String> writtenIgnorePatterns,
                                            Set<String> ccxOwnedDenyRules) {
        if (resolved.ignore() == null || resolved.ignore().isEmpty()) {
            return settings;
        }
        var existingDeny = settings.permissions() != null && settings.permissions().deny() != null
            ? settings.permissions().deny()
            : List.<String>of();

        var denyRules = new ArrayList<String>();
        for (var rawPattern : resolved.ignore()) {
            if (rawPattern == null || rawPattern.isBlank()) {
                continue;
            }
            var pattern = rawPattern.strip();
            var rule = IgnorePatterns.toReadDenyRule(pattern);
            if (denyRules.contains(rule)) {
                continue;
            }
            denyRules.add(rule);
            if (!existingDeny.contains(rule) || ccxOwnedDenyRules.contains(rule)) {
                writtenIgnorePatterns.add(pattern);
            }
        }
        if (denyRules.isEmpty()) {
            return settings;
        }
        log.debug("ignore patterns translated to deny rules rules={}", denyRules);
        return settings.withPermissions(
            mergePermissionsList(settings.permissions(), new Permissions(null, denyRules)));
    }

    /**
     * Collects the deny rules that some profile recorded in {@code context} already contributed.
     * A rule outside this set that is nevertheless present in {@code permissions.deny} belongs to
     * the user, and must never be claimed by a profile — claiming it would delete it on disable.
     */
    private static Set<String> ccxOwnedDenyRules(CcxContext context) {
        return ccxClaimedPermissions(context, CcxContext.ProfileSnapshot::ignorePatterns).stream()
            .filter(pattern -> !pattern.isBlank())
            .map(IgnorePatterns::toReadDenyRule)
            .collect(Collectors.toSet());
    }

    /**
     * Collects the permission entries that some profile recorded in {@code context} already
     * claimed, reading them through {@code snapshotField}.
     */
    private static Set<String> ccxClaimedPermissions(
        CcxContext context, Function<CcxContext.ProfileSnapshot, List<String>> snapshotField) {
        if (context == null || context.enabledProfiles() == null) {
            return Set.of();
        }
        return context.enabledProfiles().values().stream()
            .filter(Objects::nonNull)
            .map(CcxContext.ProfileRecord::snapshot)
            .filter(Objects::nonNull)
            .map(snapshotField)
            .filter(Objects::nonNull)
            .flatMap(List::stream)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    /**
     * Narrows the permission entries a profile declares down to the ones it is allowed to revoke
     * on disable: an entry absent from {@code existing} is one this apply introduced, and an entry
     * some recorded profile already claimed stays ccx-owned. Everything else was written by the
     * user and must survive the profile being disabled.
     *
     * @param declared entries the resolved profile asks for
     * @param existing entries already present in {@code settings.json} before this apply
     * @param ccxOwned entries a recorded profile snapshot already claims
     */
    private static List<String> claimedPermissions(List<String> declared, List<String> existing,
                                                   Set<String> ccxOwned) {
        if (declared == null || declared.isEmpty()) {
            return List.of();
        }
        var existingEntries = existing != null ? Set.copyOf(existing) : Set.<String>of();
        return declared.stream()
            .filter(entry -> !existingEntries.contains(entry) || ccxOwned.contains(entry))
            .distinct()
            .toList();
    }

    /**
     * Safe overload: skips removing any resource that is still claimed by a sibling
     * active profile in {@code context} (i.e. any profile other than {@code profileName}).
     */
    public void remove(CcxContext.ProfileSnapshot snapshot, Path projectRoot,
                       CcxContext context, String profileName) {
        if (snapshot == null) {
            log.debug("no snapshot recorded for profile={} — nothing to remove", profileName);
            return;
        }
        var siblingPlugins = new HashSet<String>();
        var siblingRules = new HashSet<String>();
        var siblingFragments = new HashSet<String>();
        var siblingCommands = new HashSet<String>();
        var siblingEnvKeys = new HashSet<String>();
        var siblingIgnorePatterns = new HashSet<String>();
        var siblingPermissionsAllow = new HashSet<String>();
        var siblingPermissionsDeny = new HashSet<String>();

        var recordedProfiles = context != null && context.enabledProfiles() != null
            ? context.enabledProfiles()
            : Map.<String, CcxContext.ProfileRecord>of();
        for (var entry : recordedProfiles.entrySet()) {
            if (entry.getKey().equals(profileName)) continue;
            var s = entry.getValue() != null ? entry.getValue().snapshot() : null;
            if (s == null) continue;
            if (s.plugins() != null) siblingPlugins.addAll(s.plugins());
            if (s.rules() != null) siblingRules.addAll(s.rules());
            if (s.fragments() != null) siblingFragments.addAll(s.fragments());
            if (s.commands() != null) siblingCommands.addAll(s.commands());
            if (s.env() != null) siblingEnvKeys.addAll(s.env().keySet());
            if (s.ignorePatterns() != null) siblingIgnorePatterns.addAll(s.ignorePatterns());
            if (s.permissionsAllow() != null) siblingPermissionsAllow.addAll(s.permissionsAllow());
            if (s.permissionsDeny() != null) siblingPermissionsDeny.addAll(s.permissionsDeny());
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
        var filteredAllow = snapshot.permissionsAllow() == null ? List.<String>of()
            : snapshot.permissionsAllow().stream().filter(a -> !siblingPermissionsAllow.contains(a)).toList();
        var filteredDeny = snapshot.permissionsDeny() == null ? List.<String>of()
            : snapshot.permissionsDeny().stream().filter(d -> !siblingPermissionsDeny.contains(d)).toList();

        var filteredSnapshot = new CcxContext.ProfileSnapshot(
            filteredPlugins,
            filteredRules,
            filteredAllow,
            filteredDeny,
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
        if (snapshot == null) {
            log.debug("no snapshot to remove root={}", projectRoot);
            return;
        }
        var recordedPlugins = snapshot.plugins() != null ? snapshot.plugins() : List.<String>of();
        var recordedRules = snapshot.rules() != null ? snapshot.rules() : List.<String>of();

        // Remove plugins from settings
        var settings = settingsService.readForUpdate(projectRoot);
        if (settings.enabledPlugins() != null && !recordedPlugins.isEmpty()) {
            var updated = new LinkedHashMap<>(settings.enabledPlugins());
            recordedPlugins.forEach(updated::remove);
            settingsService.write(settings.withEnabledPlugins(updated), projectRoot);
        }

        // Remove rule files
        var rulesDir = projectRoot.resolve(".claude/rules").normalize();
        for (var rule : recordedRules) {
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
            var settingsForEnv = settingsService.readForUpdate(projectRoot);
            if (settingsForEnv.env() != null) {
                var updatedEnv = new LinkedHashMap<>(settingsForEnv.env());
                snapshot.env().keySet().forEach(updatedEnv::remove);
                settingsService.write(
                    settingsForEnv.withEnv(updatedEnv.isEmpty() ? null : updatedEnv), projectRoot);
            }
        }

        // Remove the permission entries this profile contributed
        removePermissions(snapshot, projectRoot);

        log.info("profile removed plugins={} rules={}", recordedPlugins, recordedRules);
    }

    private boolean isUnderConfigDir(Path file) {
        try {
            return file.toRealPath().startsWith(ccxConfigService.configDir().toRealPath());
        } catch (IOException e) {
            log.warn("could not resolve source path path={}", file, e);
            return false;
        }
    }

    /**
     * Deletes from {@code permissions} every entry this profile claimed: the {@code allow} and
     * {@code deny} entries recorded in the snapshot, plus the deny rules
     * {@link #applyIgnoreAsDenyRules} derived from its ignore patterns. Entries the user wrote by
     * hand were never claimed in the snapshot, so they survive untouched — as do any unmodelled
     * keys inside the {@code permissions} block.
     */
    private void removePermissions(CcxContext.ProfileSnapshot snapshot, Path projectRoot) {
        var allowToRemove = snapshot.permissionsAllow() != null
            ? Set.copyOf(snapshot.permissionsAllow())
            : Set.<String>of();
        var denyToRemove = new HashSet<String>();
        if (snapshot.permissionsDeny() != null) {
            denyToRemove.addAll(snapshot.permissionsDeny());
        }
        if (snapshot.ignorePatterns() != null) {
            snapshot.ignorePatterns().stream()
                .filter(pattern -> pattern != null && !pattern.isBlank())
                .map(IgnorePatterns::toReadDenyRule)
                .forEach(denyToRemove::add);
        }
        if (allowToRemove.isEmpty() && denyToRemove.isEmpty()) {
            return;
        }
        var settings = settingsService.readForUpdate(projectRoot);
        var permissions = settings.permissions();
        if (permissions == null) {
            return;
        }
        var remainingAllow = retainAllExcept(permissions.allow(), allowToRemove);
        var remainingDeny = retainAllExcept(permissions.deny(), denyToRemove);
        if (size(remainingAllow) == size(permissions.allow())
            && size(remainingDeny) == size(permissions.deny())) {
            return;
        }
        var unknownFields = permissions.unknownFields();
        var updated = remainingAllow == null && remainingDeny == null && unknownFields.isEmpty()
            ? null
            : new Permissions(remainingAllow, remainingDeny, unknownFields.isEmpty() ? null : unknownFields);
        settingsService.write(settings.withPermissions(updated), projectRoot);
        log.debug("permission entries removed allow={} deny={}", allowToRemove, denyToRemove);
    }

    /**
     * Returns {@code entries} without any member of {@code removals}, collapsing an empty
     * result to {@code null} so the key drops out of {@code settings.json} entirely.
     */
    private static List<String> retainAllExcept(List<String> entries, Set<String> removals) {
        if (entries == null) {
            return null;
        }
        var remaining = entries.stream().filter(entry -> !removals.contains(entry)).toList();
        return remaining.isEmpty() ? null : remaining;
    }

    private static int size(List<String> entries) {
        return entries != null ? entries.size() : 0;
    }

    private Permissions mergePermissionsList(Permissions target, Permissions source) {
        var targetAllow = target != null ? target.allow() : null;
        var targetDeny = target != null ? target.deny() : null;
        var allow = source.allow() == null ? targetAllow : ListUtils.orderedUnion(targetAllow, source.allow());
        var deny = source.deny() == null ? targetDeny : ListUtils.orderedUnion(targetDeny, source.deny());
        var unknownFields = target != null ? target.unknownFields() : Map.<String, Object>of();
        return new Permissions(allow, deny, unknownFields.isEmpty() ? null : unknownFields);
    }
}
