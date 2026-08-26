package me.neveux.ccx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One entry of Claude Code's own marketplace registry
 * ({@code ~/.claude/plugins/known_marketplaces.json}), keyed by marketplace key.
 *
 * <p>Only {@code installLocation} is modelled: it is the directory Claude Code has already
 * populated for that marketplace, which makes the {@code source} descriptor and the
 * {@code lastUpdated} stamp redundant for plugin discovery.
 *
 * @param installLocation absolute path of the directory holding the marketplace contents
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaudeMarketplaceRegistryEntry(String installLocation) {}
