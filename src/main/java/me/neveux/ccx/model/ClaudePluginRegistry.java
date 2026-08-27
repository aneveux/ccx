package me.neveux.ccx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * Claude Code's plugin installation registry ({@code ~/.claude/plugins/installed_plugins.json}).
 *
 * <p>A plugin may hold several records at once — one per scope it was installed into — so the
 * value is a list, not a single entry.
 *
 * @param version registry format version written by Claude Code
 * @param plugins installation records keyed by {@code <plugin>@<marketplace>}
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaudePluginRegistry(int version, Map<String, List<ClaudePluginInstall>> plugins) {}
