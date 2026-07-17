package com.concentus.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads a YAML file into a validated {@link AgentSpec}. */
public final class ConfigLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ConfigLoader() {}

    public static AgentSpec load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Config file not found: " + path.toAbsolutePath());
        }
        AgentSpec spec = YAML.readValue(Files.readString(path), AgentSpec.class);
        spec.validate();
        return spec;
    }
}
