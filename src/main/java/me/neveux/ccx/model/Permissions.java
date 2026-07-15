package me.neveux.ccx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Tool-use permission lists for a Claude Code session, corresponding to the
 * {@code permissions} block in {@code .claude/settings.json}. Each entry is a
 * tool permission pattern (e.g. {@code "Bash(git *)"}).
 *
 * @param allow patterns for tools that are unconditionally permitted
 * @param deny  patterns for tools that are unconditionally blocked
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Permissions(List<String> allow, List<String> deny) {
}
