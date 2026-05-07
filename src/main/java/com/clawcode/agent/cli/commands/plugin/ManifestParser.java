package com.clawcode.agent.cli.commands.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawcode.agent.cli.AgentApiDtos.PluginManifest;
import com.clawcode.agent.cli.plugin.PluginConfig;

final class ManifestParser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ManifestParser() {}

    static PluginManifest parse(Path file) throws IOException {
        var manifest = MAPPER.readValue(file.toFile(), PluginManifest.class);
        validate(manifest);
        return manifest;
    }

    static PluginManifest parse(InputStream input) throws IOException {
        var manifest = MAPPER.readValue(input, PluginManifest.class);
        validate(manifest);
        return manifest;
    }

    private static void validate(PluginManifest m) {
        if (m.name() == null || m.name().isBlank()) {
            throw new PluginConfig.ValidationException("manifest missing required field: name");
        }
        if (m.id() == null || m.id().isBlank()) {
            throw new PluginConfig.ValidationException("manifest missing required field: id");
        }
    }
}
