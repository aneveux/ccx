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
 * Tool-use permission lists for a Claude Code session, corresponding to the
 * {@code permissions} block in {@code .claude/settings.json}. Each entry is a
 * tool permission pattern (e.g. {@code "Bash(git *)"}).
 *
 * <p>Keys ccx does not model (for example {@code defaultMode}, {@code ask},
 * {@code additionalDirectories}) are captured in {@code unknownFields} and written
 * back verbatim, so rewriting the file never discards user configuration.
 *
 * @param allow         patterns for tools that are unconditionally permitted
 * @param deny          patterns for tools that are unconditionally blocked
 * @param unknownFields keys inside the {@code permissions} block that ccx does not model
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(builder = Permissions.Builder.class)
public record Permissions(
    List<String> allow,
    List<String> deny,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    Map<String, Object> unknownFields
) {

    /**
     * Creates a permissions block with no unmodelled keys.
     */
    public Permissions(List<String> allow, List<String> deny) {
        this(allow, deny, null);
    }

    @JsonAnyGetter
    @Override
    public Map<String, Object> unknownFields() {
        return unknownFields != null ? unknownFields : Map.of();
    }

    /**
     * Returns a copy with {@code allow} replaced, preserving unmodelled keys.
     */
    public Permissions withAllow(List<String> allow) {
        return new Permissions(allow, deny, unknownFields);
    }

    /**
     * Returns a copy with {@code deny} replaced, preserving unmodelled keys.
     */
    public Permissions withDeny(List<String> deny) {
        return new Permissions(allow, deny, unknownFields);
    }

    /**
     * Jackson builder for {@link Permissions}, needed because
     * {@link JsonAnySetter} cannot be applied to a record component.
     */
    @RegisterForReflection
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {
        private List<String> allow;
        private List<String> deny;
        private final Map<String, Object> unknownFields = new HashMap<>();

        public Builder allow(List<String> value) { this.allow = value; return this; }
        public Builder deny(List<String> value) { this.deny = value; return this; }

        @JsonAnySetter
        public Builder unknownField(String key, Object value) {
            this.unknownFields.put(key, value);
            return this;
        }

        public Permissions build() {
            return new Permissions(allow, deny, unknownFields.isEmpty() ? null : unknownFields);
        }
    }
}
