package me.neveux.ccx.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.neveux.ccx.config.ObjectMapperConfig;
import me.neveux.ccx.model.Profile;
import me.neveux.ccx.model.ProfileClaudeMd;
import me.neveux.ccx.model.Permissions;
import me.neveux.ccx.model.ProfilePlugins;
import me.neveux.ccx.util.ListUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads, resolves, and introspects ccx profiles stored under the ccx profiles directory
 * ({@code ~/.config/ccx/profiles/<name>/profile.yaml}).
 *
 * <p>Profiles may declare an {@code extends} list to inherit from one or more parent
 * profiles. {@link #loadAndResolve} walks the full inheritance tree, merges plugins,
 * permissions, rules, env vars, and {@code ignore} patterns in depth-first order,
 * and returns a single flat {@link me.neveux.ccx.model.Profile}. Cycles in the
 * inheritance graph throw {@link ProfileCycleException}.
 */
@ApplicationScoped
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    /**
     * Profile names addressable on disk, matching the guard in
     * {@link CcxConfigService#profileDir(String)}: a name outside this set cannot be turned into a
     * path at all, so it is filtered out or reported rather than allowed to reach the filesystem.
     */
    private static final Pattern VALID_PROFILE_NAME = Pattern.compile("[a-z0-9-]+");

    private final ObjectMapper yamlMapper;
    private final CcxConfigService ccxConfigService;

    public ProfileService() {
        this.yamlMapper = ObjectMapperConfig.defaultYaml();
        this.ccxConfigService = new CcxConfigService();
    }

    @Inject
    public ProfileService(@Named("yamlMapper") ObjectMapper yamlMapper, CcxConfigService ccxConfigService) {
        this.yamlMapper = yamlMapper;
        this.ccxConfigService = ccxConfigService;
    }

    /**
     * Loads the raw (un-merged) profile YAML for the given profile name from
     * {@code <profilesDir>/<name>/profile.yaml}. Does not resolve inheritance.
     *
     * @param name profile name, must match {@code [a-z0-9-]+}
     * @return the deserialized profile
     * @throws ProfileNotFoundException if no {@code profile.yaml} exists for the name
     * @throws ProfileLoadException     if the YAML cannot be parsed
     */
    public Profile load(String name) {
        var profileYaml = ccxConfigService.profileDir(name).resolve("profile.yaml");
        if (!Files.exists(profileYaml)) {
            throw new ProfileNotFoundException(name);
        }
        try {
            var profile = yamlMapper.readValue(profileYaml.toFile(), Profile.class);
            log.debug("loaded profile name={}", name);
            return profile;
        } catch (IOException e) {
            throw new ProfileLoadException("Failed to load profile: " + name, e);
        }
    }

    /**
     * Loads all profiles found under the profiles directory. Profiles whose YAML cannot be
     * parsed are logged and skipped; they do not cause the entire load to fail.
     *
     * @return a map from profile directory name to the deserialized (un-merged) profile;
     *         empty if the profiles directory does not exist
     * @throws ProfileLoadException if the profiles directory cannot be listed
     */
    public Map<String, Profile> loadAll() {
        var profilesDir = ccxConfigService.profilesDir();
        if (!Files.exists(profilesDir)) {
            return Map.of();
        }
        var result = new HashMap<String, Profile>();
        try (var stream = Files.list(profilesDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                var yaml = dir.resolve("profile.yaml");
                if (Files.exists(yaml)) {
                    var dirName = dir.getFileName().toString();
                    if (!isValidProfileName(dirName)) {
                        log.warn("skipping profile directory with unusable name name={} path={}", dirName, dir);
                        return;
                    }
                    try {
                        var profile = yamlMapper.readValue(yaml.toFile(), Profile.class);
                        result.put(dirName, profile);
                    } catch (IOException e) {
                        log.warn("failed to load profile name={} path={}", dirName, yaml, e);
                    }
                }
            });
        } catch (IOException e) {
            throw new ProfileLoadException("Failed to list profiles from: " + profilesDir, e);
        }
        return result;
    }

    /**
     * Loads the profile named {@code name} and fully resolves its inheritance chain,
     * returning a single merged profile with all ancestor fields incorporated.
     *
     * @param name profile name, must match {@code [a-z0-9-]+}
     * @return the resolved, merged profile
     * @throws ProfileNotFoundException if {@code name} is not found in the registry
     * @throws ProfileCycleException    if the inheritance graph contains a cycle
     * @throws ProfileLoadException     if any YAML in the chain cannot be parsed
     */
    public Profile loadAndResolve(String name) {
        var registry = loadAll();
        var profile = registry.get(name);
        if (profile == null) {
            throw new ProfileNotFoundException(name);
        }
        return resolve(name, profile, registry);
    }

    /**
     * Resolves {@code profile}'s inheritance chain, tracking cycles by registry key.
     *
     * @param key      the registry key {@code profile} is filed under — the identity cycle
     *                 detection uses, since {@code metadata.name} is user-editable, may be
     *                 duplicated across profiles, and may be absent entirely
     * @param profile  the profile to resolve
     * @param registry every known profile, keyed by directory name
     */
    Profile resolve(String key, Profile profile, Map<String, Profile> registry) {
        return resolveRecursive(key, profile, registry, new HashSet<>());
    }

    private Profile resolveRecursive(String key, Profile profile, Map<String, Profile> registry, Set<String> visited) {
        if (!visited.add(key)) {
            throw new ProfileCycleException("Cycle detected at profile: " + key + " visited=" + visited);
        }
        try {
            if (profile.extend() == null || profile.extend().isEmpty()) {
                return profile;
            }

            Profile merged = null;
            for (var parentName : profile.extend()) {
                var parent = registry.get(parentName);
                if (parent == null) {
                    throw new ProfileNotFoundException("Parent profile '" + parentName + "' not found while resolving '" + key + "'. Verify the profile YAML's 'extends' field.");
                }
                var resolvedParent = resolveRecursive(parentName, parent, registry, visited);
                merged = merged == null ? resolvedParent : mergeProfiles(merged, resolvedParent);
            }

            return merged == null ? profile : mergeProfiles(merged, profile);
        } finally {
            // A profile reached twice through different branches of a diamond is legal;
            // only a profile still on the current path is a cycle.
            visited.remove(key);
        }
    }

    private Profile mergeProfiles(Profile ancestor, Profile child) {
        var metadata = child.metadata() != null ? child.metadata() : ancestor.metadata();
        var extend = child.extend();
        var plugins = mergePlugins(ancestor.plugins(), child.plugins());
        var permissions = mergePermissions(ancestor.permissions(), child.permissions());
        var claudeMd = mergeClaudeMd(ancestor.claudeMd(), child.claudeMd());
        var env = mergeEnv(ancestor.env(), child.env());
        var ignore = ListUtils.orderedUnion(ancestor.ignore(), child.ignore());

        return new Profile(metadata, extend, plugins, permissions, claudeMd, env, ignore);
    }

    private ProfilePlugins mergePlugins(ProfilePlugins ancestor, ProfilePlugins child) {
        return new ProfilePlugins(ListUtils.orderedUnion(
            ancestor != null ? ancestor.enable() : null,
            child != null ? child.enable() : null));
    }

    private Permissions mergePermissions(Permissions ancestor, Permissions child) {
        if (ancestor == null && child == null) return null;
        var allow = ListUtils.orderedUnion(
            ancestor != null ? ancestor.allow() : null,
            child != null ? child.allow() : null);
        var deny = ListUtils.orderedUnion(
            ancestor != null ? ancestor.deny() : null,
            child != null ? child.deny() : null);
        return new Permissions(allow, deny);
    }

    private ProfileClaudeMd mergeClaudeMd(ProfileClaudeMd ancestor, ProfileClaudeMd child) {
        if (ancestor == null && child == null) return null;
        return new ProfileClaudeMd(ListUtils.orderedUnion(
            ancestor != null ? ancestor.fragments() : null,
            child != null ? child.fragments() : null));
    }

    private Map<String, String> mergeEnv(Map<String, String> ancestor, Map<String, String> child) {
        if (ancestor == null && child == null) return null;
        var result = new LinkedHashMap<String, String>();
        if (ancestor != null) result.putAll(ancestor);
        if (child != null) result.putAll(child);
        return result.isEmpty() ? null : result;
    }

    /**
     * Resolve the full transitive {@code extends} closure of a profile, including the
     * profile itself. Dependencies are ordered before the profiles that extend them
     * (post-order), so the leaf profile is always last — callers that enable the whole
     * closure can apply parents before children.
     *
     * <p>A dependency whose {@code profile.yaml} is missing on disk is still included in
     * the returned set (it just cannot be recursed into). This lets dependency-drift
     * detection report a missing parent rather than silently dropping it.
     *
     * @throws ProfileCycleException if the {@code extends} graph contains a cycle
     */
    public Set<String> resolveDependencyClosure(String name) {
        var closure = new LinkedHashSet<String>();
        collectClosure(name, closure, new HashSet<>());
        return closure;
    }

    /**
     * Of a profile's transitive {@code extends} closure, the dependencies that are NOT
     * present in {@code enabledProfiles}. The profile itself is never reported. A
     * dependency whose {@code profile.yaml} is missing on disk is still reported here
     * (it is part of the closure — see {@link #resolveDependencyClosure}).
     *
     * <p>Returns dependency-first order (the closure's natural order, leaf excluded).
     */
    public List<String> missingDependencies(String name, Set<String> enabledProfiles) {
        return resolveDependencyClosure(name).stream()
            .filter(dep -> !dep.equals(name))
            .filter(dep -> !enabledProfiles.contains(dep))
            .toList();
    }

    private void collectClosure(String name, Set<String> closure, Set<String> inProgress) {
        if (closure.contains(name)) {
            return;
        }
        if (!inProgress.add(name)) {
            throw new ProfileCycleException("Cycle detected at profile: " + name + " visited=" + inProgress);
        }
        if (!isValidProfileName(name)) {
            throw new ProfileLoadException("Invalid profile name in an 'extends' chain: '" + name
                + "'. Profile names must match [a-z0-9-]+ — fix the 'extends' entry that references it.");
        }
        Profile profile = null;
        try {
            profile = load(name);
        } catch (ProfileNotFoundException e) {
            log.debug("dependency closure: profile not found on disk name={}", name);
        }
        if (profile != null && profile.extend() != null) {
            for (var parentName : profile.extend()) {
                collectClosure(parentName, closure, inProgress);
            }
        }
        inProgress.remove(name);
        closure.add(name);
    }

    /**
     * Returns the names of all profiles that have a {@code profile.yaml} file under the
     * profiles directory, sorted alphabetically.
     *
     * @return sorted list of available profile names; empty if none exist
     * @throws ProfileLoadException if the profiles directory cannot be listed
     */
    public List<String> listAvailableProfiles() {
        var profilesDir = ccxConfigService.profilesDir();
        if (!Files.exists(profilesDir)) {
            return List.of();
        }
        try (var stream = Files.list(profilesDir)) {
            return stream
                .filter(Files::isDirectory)
                .filter(dir -> Files.exists(dir.resolve("profile.yaml")))
                .map(Path::getFileName)
                .map(Path::toString)
                .filter(name -> {
                    if (isValidProfileName(name)) return true;
                    log.warn("skipping profile directory with unusable name name={}", name);
                    return false;
                })
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new ProfileLoadException("Failed to list profiles from: " + profilesDir, e);
        }
    }

    /**
     * Whether {@code name} can be used as a profile name: only these names resolve to a directory
     * under the profiles root, so listings drop everything else and the {@code extends} walk
     * reports it instead of letting the path guard throw.
     *
     * @param name candidate profile name, possibly a raw directory name or an {@code extends} entry
     * @return {@code true} when {@code name} is non-null and matches {@code [a-z0-9-]+}
     */
    public static boolean isValidProfileName(String name) {
        return name != null && VALID_PROFILE_NAME.matcher(name).matches();
    }

}
