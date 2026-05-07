package com.clawcode.agent.cli.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * File-backed store for CLI authentication credentials.
 * Persists to {@code ~/.agent-cli/auth.json}.
 *
 * <p>Thread-safe via synchronized methods. Atomic writes via temp-file + move.
 */
public class FileAuthStore {

    private static final String CONFIG_DIR_NAME = ".agent-cli";
    private static final String CONFIG_FILE_NAME = "auth.json";

    private final Path configPath;
    private final ObjectMapper mapper;

    public FileAuthStore() {
        this(defaultConfigPath());
    }

    public FileAuthStore(Path configPath) {
        this.configPath = configPath;
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Optional<AuthCredentials> load() {
        if (!Files.exists(configPath)) {
            return Optional.empty();
        }
        try {
            var tree = mapper.readTree(configPath.toFile());
            String apiKey = nullableText(tree, "apiKey");
            String apiKeyHeader = nullableText(tree, "apiKeyHeader");
            Map<String, String> customHeaders = tree.has("customHeaders")
                ? toStringMap(tree.get("customHeaders")) : Map.of();
            Instant updatedAt = tree.has("updatedAt")
                ? Instant.parse(tree.get("updatedAt").asText()) : null;
            return Optional.of(new AuthCredentials(apiKey, apiKeyHeader, customHeaders, updatedAt));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load auth config: " + configPath, e);
        }
    }

    public synchronized void save(AuthCredentials credentials) {
        try {
            Files.createDirectories(configPath.getParent());
            var root = toMap(credentials);
            var tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), root);
            Files.move(tmp, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save auth config: " + configPath, e);
        }
    }

    public synchronized void clear() {
        try {
            Files.deleteIfExists(configPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to clear auth config: " + configPath, e);
        }
    }

    public Path configPath() {
        return configPath;
    }

    // ── helpers ─────────────────────────────────────────────

    private Map<String, Object> toMap(AuthCredentials c) {
        var map = new LinkedHashMap<String, Object>();
        map.put("apiKey", c.apiKey());
        map.put("apiKeyHeader", c.apiKeyHeader());
        map.put("customHeaders", c.customHeaders());
        map.put("updatedAt", (c.updatedAt() != null ? c.updatedAt() : Instant.now()).toString());
        return map;
    }

    private static String nullableText(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    private static Map<String, String> toStringMap(com.fasterxml.jackson.databind.JsonNode node) {
        var map = new LinkedHashMap<String, String>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            map.put(entry.getKey(), entry.getValue().asText());
        }
        return Map.copyOf(map);
    }

    private static Path defaultConfigPath() {
        return Path.of(System.getProperty("user.home"), CONFIG_DIR_NAME, CONFIG_FILE_NAME);
    }
}
