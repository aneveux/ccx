package me.neveux.ccx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Root structure of {@code .ccx/context.json}, the per-project ccx state file.
 * Tracks which profiles are currently enabled and the configuration snapshot
 * that was active when each profile was applied.
 *
 * @param version         format version of this context file
 * @param enabledProfiles map from profile name to its activation record
 */
@RegisterForReflection
public record CcxContext(
    String version,
    Map<String, ProfileRecord> enabledProfiles
) {
    /** Format version written into every new context file. Bump when the schema changes. */
    public static final String FORMAT_VERSION = "1";

    /** A context with no profiles enabled, stamped with the current {@link #FORMAT_VERSION}. */
    public static CcxContext empty() {
        return new CcxContext(FORMAT_VERSION, Map.of());
    }

    /**
     * Records the moment a profile was enabled and the configuration snapshot
     * that was written into {@code .claude/settings.json} at that time.
     *
     * @param enabledAt timestamp when the profile was enabled
     * @param snapshot  configuration values contributed by the profile
     */
    public record ProfileRecord(
        Instant enabledAt,
        ProfileSnapshot snapshot
    ) {}

    /**
     * Immutable snapshot of the configuration values contributed by a profile
     * at enable time. Used to detect drift — if the live settings diverge from
     * this snapshot, the profile is considered altered.
     *
     * @param plugins           plugin keys enabled by the profile
     * @param rules             rule file paths contributed by the profile
     * @param permissionsAllow  tool permission allow-list entries
     * @param permissionsDeny   tool permission deny-list entries
     * @param commands          slash-command entries
     * @param fragments         CLAUDE.md fragment paths
     * @param env               environment variable overrides
     * @param ignorePatterns    {@code ignore} patterns applied as {@code Read(...)} deny rules,
     *                          recorded for removal tracking
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProfileSnapshot(
        List<String> plugins,
        List<String> rules,
        List<String> permissionsAllow,
        List<String> permissionsDeny,
        List<String> commands,
        List<String> fragments,
        Map<String, String> env,
        List<String> ignorePatterns
    ) {}
}
