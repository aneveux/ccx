package me.neveux.ccx.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MarketplaceSourceDeserializer")
class MarketplaceSourceDeserializerTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Nested
    @DisplayName("object-keyed format (Claude Code native)")
    class ObjectFormat {

        @Test
        @DisplayName("should parse object-keyed format into a MarketplaceSource with correct key and path")
        void deserialize_acceptsObjectFormat() throws Exception {
            var json = """
                {
                  "extraKnownMarketplaces": {
                    "my-marketplace": {
                      "source": {
                        "source": "directory",
                        "path": "/home/user/.claude/plugins"
                      }
                    }
                  }
                }
                """;

            var settings = mapper.readValue(json, Settings.class);

            assertThat(settings.extraKnownMarketplaces())
                .as("object-format marketplace must be parsed into a list of size 1")
                .hasSize(1);
            assertThat(settings.extraKnownMarketplaces().get(0).key())
                .as("parsed marketplace key must match the object key")
                .isEqualTo("my-marketplace");
            assertThat(settings.extraKnownMarketplaces().get(0).path())
                .as("parsed marketplace path must match the directory path value")
                .isEqualTo("/home/user/.claude/plugins");
        }

        @Test
        @DisplayName("should include a github source entry (no path, has repo)")
        void deserialize_includesGithubSources() throws Exception {
            var json = """
                {
                  "extraKnownMarketplaces": {
                    "github-only": {
                      "source": {
                        "source": "github",
                        "repo": "anthropics/claude-plugins-official"
                      }
                    }
                  }
                }
                """;

            var settings = mapper.readValue(json, Settings.class);

            assertThat(settings.extraKnownMarketplaces())
                .as("github-sourced marketplace must be included so MarketplaceCloneService can fetch it")
                .hasSize(1);
            assertThat(settings.extraKnownMarketplaces().get(0).key())
                .isEqualTo("github-only");
            assertThat(settings.extraKnownMarketplaces().get(0).githubRepo())
                .isEqualTo("anthropics/claude-plugins-official");
            assertThat(settings.extraKnownMarketplaces().get(0).sourceType())
                .isEqualTo("github");
        }
    }

    @Nested
    @DisplayName("array format (CCX-written)")
    class ArrayFormat {

        @Test
        @DisplayName("should parse array-format marketplaces with key and path fields")
        void deserialize_acceptsArrayFormat() throws Exception {
            var json = """
                {
                  "extraKnownMarketplaces": [
                    {"key": "local-mkt", "path": "/home/user/local-marketplace"}
                  ]
                }
                """;

            var settings = mapper.readValue(json, Settings.class);

            assertThat(settings.extraKnownMarketplaces())
                .as("array-format marketplace must be parsed into a list of size 1")
                .hasSize(1);
            assertThat(settings.extraKnownMarketplaces().get(0).key())
                .isEqualTo("local-mkt");
            assertThat(settings.extraKnownMarketplaces().get(0).path())
                .isEqualTo("/home/user/local-marketplace");
        }
    }
}
