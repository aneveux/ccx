package me.neveux.ccx.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles both CCX-written format (array of {key, path}) and real Claude Code format
 * (object keyed by name: {"key": {"source": {"source": "directory", "path": "..."}}}).
 */
@RegisterForReflection
public class MarketplaceSourceDeserializer extends StdDeserializer<List<MarketplaceSource>> {

    public MarketplaceSourceDeserializer() {
        super(List.class);
    }

    @Override
    public List<MarketplaceSource> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        var result = new ArrayList<MarketplaceSource>();

        if (parser.currentToken() == JsonToken.START_ARRAY) {
            // CCX-written format: [{key, path}, ...]
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                result.add(parser.readValueAs(MarketplaceSource.class));
            }
        } else if (parser.currentToken() == JsonToken.START_OBJECT) {
            // Claude Code native format: {"key": {"source": {"source": "...", "path": "..."}}}
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                var key = parser.currentName();
                parser.nextToken(); // move into value object
                String path = null;
                String sourceType = null;
                String githubRepo = null;
                // dig into "source" -> "path" if present
                if (parser.currentToken() == JsonToken.START_OBJECT) {
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        var field = parser.currentName();
                        parser.nextToken();
                        if ("source".equals(field) && parser.currentToken() == JsonToken.START_OBJECT) {
                            while (parser.nextToken() != JsonToken.END_OBJECT) {
                                var innerField = parser.currentName();
                                parser.nextToken();
                                if ("path".equals(innerField)) {
                                    path = parser.getText();
                                } else if ("source".equals(innerField)) {
                                    sourceType = parser.getText();
                                } else if ("repo".equals(innerField)) {
                                    githubRepo = parser.getText();
                                } else {
                                    parser.skipChildren();
                                }
                            }
                        } else {
                            parser.skipChildren();
                        }
                    }
                } else {
                    parser.skipChildren();
                }
                if (path != null || githubRepo != null) {
                    result.add(new MarketplaceSource(key, path, sourceType, githubRepo));
                }
            }
        }

        return result;
    }
}
