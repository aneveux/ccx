package me.neveux.ccx.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for a single MCP (Model Context Protocol) server entry
 * in {@code .claude/settings.json}. Supports both SSE servers ({@code type="sse"},
 * addressed by {@code url}) and stdio servers ({@code type="stdio"}, launched
 * via {@code command} and {@code args}).
 *
 * <p>Keys ccx does not model (for example {@code env}, {@code headers},
 * {@code timeout}) are captured in {@code unknownFields} and written back verbatim,
 * so rewriting the file never discards user configuration.
 *
 * @param type          transport type: {@code "sse"} or {@code "stdio"}
 * @param url           HTTP/SSE endpoint URL, used when {@code type} is {@code "sse"}
 * @param command       executable to launch, used when {@code type} is {@code "stdio"}
 * @param args          command-line arguments passed to {@code command}
 * @param unknownFields keys inside the server entry that ccx does not model
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(builder = McpServer.Builder.class)
public record McpServer(
    String type,
    String url,
    String command,
    List<String> args,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    Map<String, Object> unknownFields
) {

    /**
     * Creates a server entry with no unmodelled keys.
     */
    public McpServer(String type, String url, String command, List<String> args) {
        this(type, url, command, args, null);
    }

    @JsonAnyGetter
    @Override
    public Map<String, Object> unknownFields() {
        return unknownFields != null ? unknownFields : Map.of();
    }

    /**
     * Jackson builder for {@link McpServer}, needed because
     * {@link JsonAnySetter} cannot be applied to a record component.
     */
    @RegisterForReflection
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {
        private String type;
        private String url;
        private String command;
        private List<String> args;
        private final Map<String, Object> unknownFields = new HashMap<>();

        public Builder type(String value) { this.type = value; return this; }
        public Builder url(String value) { this.url = value; return this; }
        public Builder command(String value) { this.command = value; return this; }
        public Builder args(List<String> value) { this.args = value; return this; }

        @JsonAnySetter
        public Builder unknownField(String key, Object value) {
            this.unknownFields.put(key, value);
            return this;
        }

        public McpServer build() {
            return new McpServer(type, url, command, args, unknownFields.isEmpty() ? null : unknownFields);
        }
    }
}
