package com.concentus.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link ConfigLoader}: reading a YAML file into a validated {@link AgentSpec}. */
class ConfigLoaderTest {

    @TempDir
    Path dir;

    @Test
    void loadsAValidYamlFileIntoAValidatedSpec() throws IOException {
        Path file = dir.resolve("agent.yaml");
        Files.writeString(file, """
                name: My Agent
                systemPrompt: Be helpful.
                model:
                  id: claude-opus-4-8
                  maxTokens: 8000
                  effort: high
                """);

        AgentSpec spec = ConfigLoader.load(file);

        assertThat(spec.name).isEqualTo("My Agent");
        assertThat(spec.systemPrompt).isEqualTo("Be helpful.");
        assertThat(spec.model.maxTokens).isEqualTo(8000);
        assertThat(spec.mode).isEqualTo("managed"); // default, unset in the YAML
    }

    @Test
    void unknownYamlPropertiesAreIgnoredRatherThanFailing() throws IOException {
        Path file = dir.resolve("agent.yaml");
        Files.writeString(file, """
                name: My Agent
                someFutureField: whatever
                model:
                  id: claude-opus-4-8
                  maxTokens: 1000
                """);

        AgentSpec spec = ConfigLoader.load(file);
        assertThat(spec.name).isEqualTo("My Agent");
    }

    @Test
    void throwsIoExceptionWhenFileDoesNotExist() {
        Path missing = dir.resolve("nope.yaml");

        assertThatThrownBy(() -> ConfigLoader.load(missing))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void throwsWhenTheParsedSpecFailsValidation() throws IOException {
        Path file = dir.resolve("invalid.yaml");
        // AgentSpec defaults `name`/`model.id` to valid values, so force validate() to fail via
        // an unrecognized `mode` instead (name/model left at their valid defaults).
        Files.writeString(file, """
                mode: bogus-mode
                """);

        assertThatThrownBy(() -> ConfigLoader.load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown mode");
    }
}
