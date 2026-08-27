package me.neveux.ccx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One installation record of Claude Code's plugin registry
 * ({@code ~/.claude/plugins/installed_plugins.json}), held in a list keyed by
 * {@code <plugin>@<marketplace>}.
 *
 * <p>Enabling a plugin in {@code settings.json} is not enough for Claude Code to load it: a
 * matching record must also exist here. A record applies to a project when its scope is
 * {@code user} or {@code managed} (both global), or when its scope is {@code project} or
 * {@code local} and {@code projectPath} is that project.
 *
 * @param scope       one of {@code user}, {@code project}, {@code local}, {@code managed}
 * @param projectPath absolute path of the project the record is scoped to; null for global scopes
 * @param installPath directory holding the materialized plugin contents
 * @param version     resolved version — a semver string or a 12-character commit sha
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaudePluginInstall(String scope, String projectPath, String installPath, String version) {}
