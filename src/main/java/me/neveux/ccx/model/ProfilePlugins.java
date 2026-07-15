package me.neveux.ccx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Plugin configuration block within a profile, listing the plugin keys
 * that should be enabled in {@code .claude/settings.json} when this
 * profile is applied.
 *
 * @param enable ordered list of plugin keys to enable (e.g. {@code "java-quarkus"})
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProfilePlugins(List<String> enable) {
}
