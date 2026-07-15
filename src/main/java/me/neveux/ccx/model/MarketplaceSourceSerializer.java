package me.neveux.ccx.model;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.util.List;

/**
 * Serializes the {@code extraKnownMarketplaces} list from the internal
 * {@link MarketplaceSource} representation into the native Claude Code format:
 * a JSON object keyed by source name, where each value wraps the source config
 * under a nested {@code "source"} object.
 *
 * <p>Inverse of {@link MarketplaceSourceDeserializer}.
 */
@RegisterForReflection
public class MarketplaceSourceSerializer extends StdSerializer<List<MarketplaceSource>> {

    public MarketplaceSourceSerializer() {
        super(List.class, false);
    }

    @Override
    public void serialize(List<MarketplaceSource> sources, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeStartObject();
        for (var source : sources) {
            gen.writeFieldName(source.key());
            gen.writeStartObject();
            gen.writeFieldName("source");
            gen.writeStartObject();
            if ("github".equals(source.sourceType())) {
                gen.writeStringField("source", "github");
                gen.writeStringField("repo", source.githubRepo());
            } else {
                gen.writeStringField("source", "directory");
                gen.writeStringField("path", source.path());
            }
            gen.writeEndObject();
            gen.writeEndObject();
        }
        gen.writeEndObject();
    }
}
