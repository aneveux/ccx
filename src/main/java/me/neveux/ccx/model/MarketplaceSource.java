package me.neveux.ccx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Describes a single extra marketplace source registered in {@code settings.json}
 * under {@code extraKnownMarketplaces}. A source can be a local directory or a
 * remote GitHub repository, controlled by {@code sourceType}.
 *
 * <p>Supports two source types:
 * <ul>
 *   <li>{@code "directory"} (default) — a local filesystem path</li>
 *   <li>{@code "github"} — a GitHub repository referenced by {@code githubRepo}</li>
 * </ul>
 *
 * @param key        logical name identifying this marketplace source
 * @param path       filesystem path used when {@code sourceType} is {@code "directory"}
 * @param sourceType {@code "directory"} or {@code "github"}; defaults to {@code "directory"} if null
 * @param githubRepo GitHub repository slug used when {@code sourceType} is {@code "github"}
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketplaceSource(String key, String path, String sourceType, String githubRepo) {
    public MarketplaceSource {
        if (sourceType == null) sourceType = "directory";
    }
}
