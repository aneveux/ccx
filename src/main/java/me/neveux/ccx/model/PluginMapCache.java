package me.neveux.ccx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

/**
 * Persisted cache of the resolved plugin map, stored to avoid repeated git
 * clone/fetch operations on every ccx invocation. Entries map plugin keys
 * to their local filesystem paths; descriptions map plugin keys to their
 * human-readable descriptions.
 *
 * @param generatedAt timestamp when this cache was last rebuilt
 * @param entries     map from plugin key to resolved local filesystem path
 * @param descriptions map from plugin key to plugin description
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record PluginMapCache(Instant generatedAt, Map<String, String> entries, Map<String, String> descriptions) {
}
