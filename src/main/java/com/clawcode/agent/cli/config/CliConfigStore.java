package com.clawcode.agent.cli.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * File-backed store for CLI configuration key-value pairs.
 * Persists to {@code ~/.agent-cli/config.json}.
 *
 * <p>Thread-safe via synchronized methods. Atomic writes via temp-file + move.
 */
public class CliConfigStore {

    private static final String CONFIG_DIR_NAME = ".agent-cli";
    private static final String CONFIG_FILE_NAME = "config.json";

    private final Path configPath;
    private final ObjectMapper mapper;

    public CliConfigStore() {
        this(defaultConfigPath());
    }

    public CliConfigStore(Path configPath) {
        this.configPath = configPath;
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Optional<String> get(String key) {
        var stored = load();
        if (stored.containsKey(key)) {
            return Optional.of(stored.get(key));
        }
        return Optional.ofNullable(ConfigKeySpec.defaultValue(key));
    }

    public synchronized void set(String key, String value) {
        ConfigKeySpec.validate(key, value);
        var entries = new LinkedHashMap<>(load());
        entries.put(key, value.strip());
        saveEntries(entries);
    }

    public synchronized boolean unset(String key) {
        var entries = new LinkedHashMap<>(load());
        if (!entries.containsKey(key)) {
            return false;
        }
        entries.remove(key);
        saveEntries(entries);
        return true;
    }

    public Map<String, String> list() {
        var stored = load();
        var result = new LinkedHashMap<String, String>();
        for (String key : ConfigKeySpec.knownKeys()) {
            result.put(key, stored.getOrDefault(key, ConfigKeySpec.defaultValue(key)));
        }
        // Include any unknown keys that were stored (future-proofing)
        for (var entry : stored.entrySet()) {
            if (!result.containsKey(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(result);
    }

    public Map<String, String> listStored() {
        return Map.copyOf(load());
    }

    public Path configPath() {
        return configPath;
    }

    // ── internal ────────────────────────────────────────────

    private Map<String, String> load() {
        if (!Files.exists(configPath)) {
            return Map.of();
        }
        try {
            var tree = mapper.readTree(configPath.toFile());
            var map = new LinkedHashMap<String, String>();
            var fields = tree.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                map.put(entry.getKey(), entry.getValue().asText());
            }
            return map;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load config: " + configPath, e);
        }
    }

    private void saveEntries(Map<String, String> entries) {
        try {
            Files.createDirectories(configPath.getParent());
            var tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), entries);
            Files.move(tmp, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save config: " + configPath, e);
        }
    }

    private static Path defaultConfigPath() {
        return Path.of(System.getProperty("user.home"), CONFIG_DIR_NAME, CONFIG_FILE_NAME);
    }
}
