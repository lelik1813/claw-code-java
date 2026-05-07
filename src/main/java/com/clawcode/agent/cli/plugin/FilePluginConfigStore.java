package com.clawcode.agent.cli.plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * File-backed store for plugin configurations.
 * Persists to {@code ~/.agent-cli/plugins.json}.
 *
 * <p>Thread-safe via synchronized methods. Atomic writes via temp-file + move.
 */
public class FilePluginConfigStore {

    private static final String CONFIG_DIR_NAME = ".agent-cli";
    private static final String CONFIG_FILE_NAME = "plugins.json";

    private final Path configPath;
    private final ObjectMapper mapper;

    public FilePluginConfigStore() {
        this(defaultConfigPath());
    }

    public FilePluginConfigStore(Path configPath) {
        this.configPath = configPath;
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public List<PluginConfig> load() {
        if (!Files.exists(configPath)) {
            return List.of();
        }
        try {
            var tree = mapper.readTree(configPath.toFile());
            var plugins = tree.has("plugins") ? tree.get("plugins") : tree;
            var result = new ArrayList<PluginConfig>();
            for (var node : plugins) {
                result.add(new PluginConfig(
                    node.get("name").asText(),
                    node.get("id").asText(),
                    PluginConfig.PluginSource.parse(node.get("source").asText()),
                    nullableText(node, "version"),
                    node.has("enabled") ? node.get("enabled").asBoolean() : true,
                    node.has("installedAt") ? Instant.parse(node.get("installedAt").asText()) : null,
                    nullableText(node, "pathOrUrl")
                ));
            }
            return List.copyOf(result);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load plugin config: " + configPath, e);
        }
    }

    public synchronized void save(List<PluginConfig> configs) {
        try {
            Files.createDirectories(configPath.getParent());
            var entries = configs.stream()
                .map(this::toMap)
                .toList();
            var root = Map.of("plugins", entries);
            var tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), root);
            Files.move(tmp, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save plugin config: " + configPath, e);
        }
    }

    public synchronized PluginConfig add(PluginConfig config) {
        var byName = new LinkedHashMap<String, PluginConfig>();
        var byId = new LinkedHashMap<String, PluginConfig>();
        for (var existing : load()) {
            byName.put(existing.name(), existing);
            byId.put(existing.id(), existing);
        }
        if (byName.containsKey(config.name())) {
            throw new PluginConfig.ValidationException(
                "plugin with name '" + config.name() + "' already exists. Remove it first or use a different name.");
        }
        if (byId.containsKey(config.id())) {
            throw new PluginConfig.ValidationException(
                "plugin with id '" + config.id() + "' already exists. Remove it first or use a different id.");
        }
        byName.put(config.name(), config);
        save(List.copyOf(byName.values()));
        return config;
    }

    public synchronized boolean remove(String name) {
        var byName = new LinkedHashMap<String, PluginConfig>();
        for (var existing : load()) {
            byName.put(existing.name(), existing);
        }
        if (!byName.containsKey(name)) {
            return false;
        }
        byName.remove(name);
        save(List.copyOf(byName.values()));
        return true;
    }

    public Optional<PluginConfig> find(String name) {
        return load().stream()
            .filter(c -> c.name().equals(name))
            .findFirst();
    }

    public synchronized PluginConfig updateEnabled(String name, boolean enabled) {
        var configs = new ArrayList<>(load());
        for (int i = 0; i < configs.size(); i++) {
            var c = configs.get(i);
            if (c.name().equals(name)) {
                var updated = new PluginConfig(c.name(), c.id(), c.source(),
                    c.version(), enabled, c.installedAt(), c.pathOrUrl());
                configs.set(i, updated);
                save(configs);
                return updated;
            }
        }
        throw new PluginConfig.ValidationException("plugin '" + name + "' not found");
    }

    public Path configPath() {
        return configPath;
    }

    // ── helpers ─────────────────────────────────────────────

    private Map<String, Object> toMap(PluginConfig c) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", c.name());
        map.put("id", c.id());
        map.put("source", c.source().name());
        if (c.version() != null) map.put("version", c.version());
        map.put("enabled", c.enabled());
        if (c.installedAt() != null) map.put("installedAt", c.installedAt().toString());
        if (c.pathOrUrl() != null) map.put("pathOrUrl", c.pathOrUrl());
        return map;
    }

    private static String nullableText(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    private static Path defaultConfigPath() {
        return Path.of(System.getProperty("user.home"), CONFIG_DIR_NAME, CONFIG_FILE_NAME);
    }
}
