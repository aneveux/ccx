package me.neveux.ccx.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Profile YAML deserialization for built-in profiles")
class ProfileSerdeTest {

    private ObjectMapper yamlMapper;

    @BeforeEach
    void setUp() {
        yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    @Test
    @DisplayName("env map round-trips through YAML")
    void profile_env_roundTrips() throws Exception {
        var yaml = "metadata:\n  name: test\nenv:\n  FOO: bar\n  ENABLE_LSP_TOOL: \"1\"\n";
        var profile = yamlMapper.readValue(yaml, Profile.class);
        assertThat(profile.env())
            .containsEntry("FOO", "bar")
            .containsEntry("ENABLE_LSP_TOOL", "1");
    }

    @Test
    @DisplayName("ignore list round-trips through YAML")
    void profile_ignore_roundTrips() throws Exception {
        var yaml = "metadata:\n  name: test\nignore:\n  - target/\n  - .mvn/\n";
        var profile = yamlMapper.readValue(yaml, Profile.class);
        assertThat(profile.ignore()).containsExactly("target/", ".mvn/");
    }

    @Test
    @DisplayName("claudeMd.fragments list round-trips through YAML")
    void profileClaudeMd_fragments_roundTrips() throws Exception {
        var yaml = "metadata:\n  name: test\nclaudeMd:\n  fragments:\n    - /abs/path/java.md\n";
        var profile = yamlMapper.readValue(yaml, Profile.class);
        assertThat(profile.claudeMd().fragments()).containsExactly("/abs/path/java.md");
    }
}
