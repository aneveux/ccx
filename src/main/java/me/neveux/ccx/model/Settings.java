package me.neveux.ccx.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Root structure of {@code .claude/settings.json}, the Claude Code project
 * configuration file managed by ccx. This record is the authoritative Java
 * representation of that file — all reads and writes go through it.
 *
 * <p>Deserialization uses {@link Builder} to handle the dual format of
 * {@code extraKnownMarketplaces} (ccx array vs. Claude Code native object)
 * and to preserve unknown fields round-trip via {@link Builder#unknownField}.
 *
 * <p>Immutable updates follow the wither pattern: each {@code withX} method
 * returns a new {@code Settings} instance with a single field replaced.
 *
 * @param enabledPlugins           map from plugin key to enabled flag
 * @param env                      environment variables injected into Claude Code sessions
 * @param skillOverrides           map from skill name to override path
 * @param mcpServers               named MCP server configurations
 * @param permissions              tool-use allow/deny lists
 * @param extraKnownMarketplaces   additional marketplace sources beyond the built-in default
 * @param hooks                    raw Claude Code hooks configuration (passed through opaquely)
 * @param unknownFields            unknown top-level fields preserved for round-trip fidelity
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(builder = Settings.Builder.class)
public record Settings(
    Map<String, Boolean> enabledPlugins,
    Map<String, String> env,
    Map<String, String> skillOverrides,
    Map<String, McpServer> mcpServers,
    Permissions permissions,
    @JsonSerialize(using = MarketplaceSourceSerializer.class)
    @JsonDeserialize(using = MarketplaceSourceDeserializer.class)
    List<MarketplaceSource> extraKnownMarketplaces,
    @JsonProperty("hooks") Map<String, Object> hooks,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    Map<String, Object> unknownFields
) {

    public Settings() {
        this(null, null, null, null, null, null, null, null);
    }

    @JsonAnyGetter
    @Override
    public Map<String, Object> unknownFields() {
        return unknownFields != null ? unknownFields : Map.of();
    }

    public Settings withEnabledPlugins(Map<String, Boolean> enabledPlugins) {
        return new Settings(enabledPlugins, env, skillOverrides, mcpServers, permissions, extraKnownMarketplaces, hooks, unknownFields);
    }

    public Settings withEnv(Map<String, String> env) {
        return new Settings(enabledPlugins, env, skillOverrides, mcpServers, permissions, extraKnownMarketplaces, hooks, unknownFields);
    }

    public Settings withSkillOverrides(Map<String, String> skillOverrides) {
        return new Settings(enabledPlugins, env, skillOverrides, mcpServers, permissions, extraKnownMarketplaces, hooks, unknownFields);
    }

    public Settings withMcpServers(Map<String, McpServer> mcpServers) {
        return new Settings(enabledPlugins, env, skillOverrides, mcpServers, permissions, extraKnownMarketplaces, hooks, unknownFields);
    }

    public Settings withPermissions(Permissions permissions) {
        return new Settings(enabledPlugins, env, skillOverrides, mcpServers, permissions, extraKnownMarketplaces, hooks, unknownFields);
    }

    public Settings withExtraKnownMarketplaces(List<MarketplaceSource> extraKnownMarketplaces) {
        return new Settings(enabledPlugins, env, skillOverrides, mcpServers, permissions, extraKnownMarketplaces, hooks, unknownFields);
    }

    public Settings withHooks(Map<String, Object> hooks) {
        return new Settings(enabledPlugins, env, skillOverrides, mcpServers, permissions, extraKnownMarketplaces, hooks, unknownFields);
    }

    /**
     * Jackson builder for {@link Settings}. Required because the record uses
     * {@link com.fasterxml.jackson.annotation.JsonAnySetter} to capture unknown
     * top-level fields for round-trip fidelity — Jackson cannot apply
     * {@code @JsonAnySetter} directly to a record component, so a builder is needed.
     *
     * <p>{@link #unknownField(String, Object)} accumulates any unrecognized keys;
     * {@link #build()} passes a {@code null} map when no unknown fields were seen,
     * which {@link JsonInclude#NON_EMPTY} then omits from serialization.
     */
    @RegisterForReflection
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {
        private Map<String, Boolean> enabledPlugins;
        private Map<String, String> env;
        private Map<String, String> skillOverrides;
        private Map<String, McpServer> mcpServers;
        private Permissions permissions;
        @JsonDeserialize(using = MarketplaceSourceDeserializer.class)
        private List<MarketplaceSource> extraKnownMarketplaces;
        private Map<String, Object> hooks;
        private final Map<String, Object> unknownFields = new HashMap<>();

        public Builder enabledPlugins(Map<String, Boolean> v) { this.enabledPlugins = v; return this; }
        public Builder env(Map<String, String> v) { this.env = v; return this; }
        public Builder skillOverrides(Map<String, String> v) { this.skillOverrides = v; return this; }
        public Builder mcpServers(Map<String, McpServer> v) { this.mcpServers = v; return this; }
        public Builder permissions(Permissions v) { this.permissions = v; return this; }
        public Builder extraKnownMarketplaces(List<MarketplaceSource> v) { this.extraKnownMarketplaces = v; return this; }
        public Builder hooks(Map<String, Object> v) { this.hooks = v; return this; }

        @JsonAnySetter
        public Builder unknownField(String key, Object value) {
            this.unknownFields.put(key, value);
            return this;
        }

        public Settings build() {
            return new Settings(enabledPlugins, env, skillOverrides, mcpServers, permissions, extraKnownMarketplaces, hooks,
                unknownFields.isEmpty() ? null : unknownFields);
        }
    }
}
