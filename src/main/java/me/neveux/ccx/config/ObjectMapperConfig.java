package me.neveux.ccx.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;

/**
 * CDI producer for the shared Jackson {@link ObjectMapper} instances used across ccx.
 *
 * <p>Produces two named beans:
 * <ul>
 *   <li>{@code @Named("jsonMapper")} — a {@link com.fasterxml.jackson.databind.json.JsonMapper}
 *       for reading and writing JSON files ({@code settings.json}, {@code context.json}, etc.).</li>
 *   <li>{@code @Named("yamlMapper")} — a {@link com.fasterxml.jackson.dataformat.yaml.YAMLMapper}
 *       for reading profile YAML files.</li>
 * </ul>
 *
 * <p>Both mappers share the same runtime configuration: case-insensitive property
 * matching, {@link com.fasterxml.jackson.datatype.jsr310.JavaTimeModule} for
 * {@link java.time.Instant} support, ISO-8601 date strings (not timestamps), and
 * silent ignoring of unknown fields. Static factory methods ({@link #defaultJson()} and
 * {@link #defaultYaml()}) provide the same configuration without CDI, for use in
 * no-arg constructors and tests.
 */
@ApplicationScoped
public class ObjectMapperConfig {

    /**
     * CDI producer for the shared JSON mapper.
     *
     * @return a configured {@link ObjectMapper} for JSON
     */
    @Produces
    @Named("jsonMapper")
    public ObjectMapper jsonMapper() {
        return configureRuntime(JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .build());
    }

    /**
     * CDI producer for the shared YAML mapper.
     *
     * @return a configured {@link ObjectMapper} for YAML
     */
    @Produces
    @Named("yamlMapper")
    public ObjectMapper yamlMapper() {
        return configureRuntime(YAMLMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .build());
    }

    /**
     * Returns a new JSON mapper with the standard ccx configuration. Intended for use in
     * no-arg constructors and tests where CDI injection is not available.
     *
     * @return a configured {@link ObjectMapper} for JSON
     */
    public static ObjectMapper defaultJson() {
        return configureRuntime(JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .build());
    }

    /**
     * Returns a new YAML mapper with the standard ccx configuration. Intended for use in
     * no-arg constructors and tests where CDI injection is not available.
     *
     * @return a configured {@link ObjectMapper} for YAML
     */
    public static ObjectMapper defaultYaml() {
        return configureRuntime(YAMLMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .build());
    }

    private static ObjectMapper configureRuntime(ObjectMapper mapper) {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}
