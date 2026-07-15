package me.neveux.ccx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Display metadata for a ccx profile, corresponding to the {@code metadata}
 * block in {@code profile.yaml}. Used in the TUI listing and the {@code ccx profile list}
 * command output.
 *
 * @param name        short identifier of the profile (matches the directory name)
 * @param description human-readable summary of what this profile configures
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProfileMetadata(String name, String description) {
}
