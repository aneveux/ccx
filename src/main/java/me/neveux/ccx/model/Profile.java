package me.neveux.ccx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * Deserialized representation of a ccx profile ({@code profile.yaml}).
 * Profiles are named configuration bundles stored under
 * {@code ~/.config/ccx/profiles/<name>/profile.yaml} and define the plugins,
 * rules, permissions, CLAUDE.md fragments, and environment variables that
 * should be applied when the profile is enabled on a project.
 *
 * <p>Profiles support extension via the {@code extends} field, allowing one
 * profile to inherit and override another. The {@code withX} methods return
 * new instances with a single field replaced, following the record wither
 * pattern for immutable updates.
 *
 * @param metadata   display name and description shown in the TUI
 * @param extend     names of profiles this profile inherits from
 * @param plugins    plugin enablement configuration
 * @param permissions tool-use permission allow/deny lists
 * @param claudeMd   CLAUDE.md fragment paths to compose
 * @param env        environment variable overrides
 * @param ignore     file path patterns applied as {@code Read(...)} deny rules in
 *                   {@code .claude/settings.json}
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record Profile(
    ProfileMetadata metadata,
    @JsonProperty("extends") List<String> extend,
    ProfilePlugins plugins,
    Permissions permissions,
    ProfileClaudeMd claudeMd,
    Map<String, String> env,
    List<String> ignore
) {

    public Profile() {
        this(null, null, null, null, null, null, null);
    }

    public Profile withMetadata(ProfileMetadata metadata) {
        return new Profile(metadata, extend, plugins, permissions, claudeMd, env, ignore);
    }

    public Profile withExtend(List<String> extend) {
        return new Profile(metadata, extend, plugins, permissions, claudeMd, env, ignore);
    }

    public Profile withPlugins(ProfilePlugins plugins) {
        return new Profile(metadata, extend, plugins, permissions, claudeMd, env, ignore);
    }

    public Profile withPermissions(Permissions permissions) {
        return new Profile(metadata, extend, plugins, permissions, claudeMd, env, ignore);
    }

    public Profile withClaudeMd(ProfileClaudeMd claudeMd) {
        return new Profile(metadata, extend, plugins, permissions, claudeMd, env, ignore);
    }

    public Profile withEnv(Map<String, String> env) {
        return new Profile(metadata, extend, plugins, permissions, claudeMd, env, ignore);
    }

    public Profile withIgnore(List<String> ignore) {
        return new Profile(metadata, extend, plugins, permissions, claudeMd, env, ignore);
    }
}
