package me.neveux.ccx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * CLAUDE.md composition configuration for a profile. Lists the fragment files
 * (relative paths under the profile directory) that should be concatenated
 * into the project's {@code CLAUDE.md} when the profile is enabled.
 *
 * @param fragments ordered list of fragment file paths to include
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProfileClaudeMd(List<String> fragments) {
}
