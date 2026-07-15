package me.neveux.ccx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Top-level structure of the ccx marketplace manifest, listing all available
 * plugins that can be installed from the marketplace.
 *
 * @param plugins ordered list of plugins available in the marketplace
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketplaceManifest(List<MarketplacePlugin> plugins) {
}
