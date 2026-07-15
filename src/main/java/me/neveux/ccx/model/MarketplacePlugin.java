package me.neveux.ccx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A single plugin entry from the ccx marketplace manifest, carrying the
 * plugin's identifier and human-readable description as returned by the
 * remote manifest JSON.
 *
 * @param name        unique identifier of the plugin (e.g. {@code "java-quarkus"})
 * @param description short human-readable description of what the plugin provides
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketplacePlugin(String name, String description) {
}
