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
        return resolve(profile, registry);
    }

    Profile resolve(Profile profile, Map<String, Profile> registry) {
        var visited = new HashSet<String>();
        return resolveRecursive(profile, registry, visited);
    }

    private Profile resolveRecursive(Profile profile, Map<String, Profile> registry, Set<String> visited) {
        var name = profile.metadata() != null ? profile.metadata().name() : null;
        if (name != null) {
            if (visited.contains(name)) {
                throw new ProfileCycleException("Cycle detected at profile: " + name + " visited=" + visited);
            }
            visited.add(name);
        }

        if (profile.extend() == null || profile.extend().isEmpty()) {
            if (name != null) visited.remove(name);
            return profile;
        }

        Profile merged = null;
        for (var parentName : profile.extend()) {
            var parent = registry.get(parentName);
            if (parent == null) {
                throw new ProfileNotFoundException("Parent profile '" + parentName + "' not found while resolving '" + name + "'. Verify the profile YAML's 'extends' field.");
            }
            var resolvedParent = resolveRecursive(parent, registry, visited);
            if (merged == null) {
                merged = resolvedParent;
            } else {
                merged = mergeProfiles(merged, resolvedParent);
            }
        }

        if (name != null) visited.remove(name);

        if (merged == null) {
            return profile;
        }
        return mergeProfiles(merged, profile);
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
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new ProfileLoadException("Failed to list profiles from: " + profilesDir, e);
        }
    }

}
