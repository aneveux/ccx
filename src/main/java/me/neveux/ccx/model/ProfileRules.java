package me.neveux.ccx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Rule file configuration block within a profile, enumerating the rule
 * markdown files that should be registered in {@code .claude/settings.json}
 * when this profile is enabled.
 *
 * @param files ordered list of rule file paths (relative to the profile directory)
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProfileRules(List<String> files) {
}
