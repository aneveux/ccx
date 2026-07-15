package me.neveux.ccx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Configuration for a single MCP (Model Context Protocol) server entry
 * in {@code .claude/settings.json}. Supports both SSE servers ({@code type="sse"},
 * addressed by {@code url}) and stdio servers ({@code type="stdio"}, launched
 * via {@code command} and {@code args}).
 *
 * @param type    transport type: {@code "sse"} or {@code "stdio"}
 * @param url     HTTP/SSE endpoint URL, used when {@code type} is {@code "sse"}
 * @param command executable to launch, used when {@code type} is {@code "stdio"}
 * @param args    command-line arguments passed to {@code command}
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpServer(String type, String url, String command, List<String> args) {
}
