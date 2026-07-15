package me.neveux.ccx.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MarketplaceSource serializer/deserializer round-trip")
class MarketplaceSourceSerdeTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Test
    @DisplayName("round-trips a directory source through serialize + deserialize")
    void roundTrip_when_directorySource_then_keyPathTypePreserved() throws Exception {
        var original = new Settings().withExtraKnownMarketplaces(
            List.of(new MarketplaceSource("dir-mkt", "/some/path", "directory", null)));

        var json = mapper.writeValueAsString(original);
        var restored = mapper.readValue(json, Settings.class);

        assertThat(restored.extraKnownMarketplaces()).hasSize(1);
        var source = restored.extraKnownMarketplaces().get(0);
        assertThat(source.key()).isEqualTo("dir-mkt");
        assertThat(source.path()).isEqualTo("/some/path");
        assertThat(source.sourceType()).isEqualTo("directory");
    }

    @Test
    @DisplayName("round-trips a github source preserving repo and source type")
    void roundTrip_when_githubSource_then_repoAndTypePreserved() throws Exception {
        var original = new Settings().withExtraKnownMarketplaces(
            List.of(new MarketplaceSource("gh-mkt", null, "github", "owner/repo")));

        var json = mapper.writeValueAsString(original);
        var restored = mapper.readValue(json, Settings.class);

        assertThat(restored.extraKnownMarketplaces()).hasSize(1);
        var source = restored.extraKnownMarketplaces().get(0);
        assertThat(source.key()).isEqualTo("gh-mkt");
        assertThat(source.githubRepo()).isEqualTo("owner/repo");
        assertThat(source.sourceType()).isEqualTo("github");
    }

    @Test
    @DisplayName("github source survives the round-trip via recursive comparison")
    void roundTrip_when_githubSource_then_recursivelyEqual() throws Exception {
        var expected = new MarketplaceSource("gh-mkt", null, "github", "owner/repo");
        var original = new Settings().withExtraKnownMarketplaces(List.of(expected));

        var json = mapper.writeValueAsString(original);
        var restored = mapper.readValue(json, Settings.class);

        assertThat(restored.extraKnownMarketplaces().get(0))
            .usingRecursiveComparison()
            .isEqualTo(expected);
    }
}
