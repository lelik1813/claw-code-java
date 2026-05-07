package com.clawcode.agent.cli.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * File-backed store for MCP server configurations.
 * Persists to {@code ~/.agent-cli/mcp-servers.json}.
 *
 * <p>Thread-safe via synchronized methods. Atomic writes via temp-file + move.
 */
public class FileMcpConfigStore {

    private static final String CONFIG_DIR_NAME = ".agent-cli";
    private static final String CONFIG_FILE_NAME = "mcp-servers.json";

    private final Path configPath;
    private final ObjectMapper mapper;

    public FileMcpConfigStore() {
        this(defaultConfigPath());
    }

    public FileMcpConfigStore(Path configPath) {
        this.configPath = configPath;
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public List<McpServerConfig> load() {
        if (!Files.exists(configPath)) {
            return List.of();
        }
        try {
            var tree = mapper.readTree(configPath.toFile());
            var servers = tree.has("servers") ? tree.get("servers") : tree;
            var result = new ArrayList<McpServerConfig>();
            for (var node : servers) {
                result.add(new McpServerConfig(
                    node.get("name").asText(),
                    McpServerConfig.McpTransport.parse(node.get("transport").asText()),
                    nullableText(node, "url"),
                    nullableText(node, "command"),
                    node.has("args") ? toList(node.get("args")) : List.of(),
                    node.has("env") ? toMap(node.get("env")) : Map.of(),
                    nullableText(node, "authToken"),
                    node.has("enabled") ? node.get("enabled").asBoolean() : true
                ));
            }
            return List.copyOf(result);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load MCP config: " + configPath, e);
        }
    }

    public synchronized void save(List<McpServerConfig> configs) {
        try {
            Files.createDirectories(configPath.getParent());
            var entries = configs.stream()
                .map(this::toMap)
                .toList();
            var root = Map.of("servers", entries);
            var tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), root);
            Files.move(tmp, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save MCP config: " + configPath, e);
        }
    }

    public synchronized McpServerConfig add(McpServerConfig config) {
        var servers = new LinkedHashMap<String, McpServerConfig>();
        for (var existing : load()) {
            servers.put(existing.name(), existing);
        }
        if (servers.containsKey(config.name())) {
            throw new McpServerConfig.ValidationException(
                "server '" + config.name() + "' already exists. Remove it first or use a different name.");
        }
        servers.put(config.name(), config);
        save(List.copyOf(servers.values()));
        return config;
    }

    public synchronized boolean remove(String name) {
        var servers = new LinkedHashMap<String, McpServerConfig>();
        for (var existing : load()) {
            servers.put(existing.name(), existing);
        }
        if (!servers.containsKey(name)) {
            return false;
        }
        servers.remove(name);
        save(List.copyOf(servers.values()));
        return true;
    }

    public Optional<McpServerConfig> find(String name) {
        return load().stream()
            .filter(c -> c.name().equals(name))
            .findFirst();
    }

    public Path configPath() {
        return configPath;
    }

    public synchronized McpServerConfig updateEnabled(String name, boolean enabled) {
        var servers = new ArrayList<>(load());
        for (int i = 0; i < servers.size(); i++) {
            var c = servers.get(i);
            if (c.name().equals(name)) {
                var updated = new McpServerConfig(c.name(), c.transport(), c.url(),
                    c.command(), c.args(), c.env(), c.authToken(), enabled);
                servers.set(i, updated);
                save(servers);
                return updated;
            }
        }
        throw new McpServerConfig.ValidationException("server '" + name + "' not found");
    }

    // ── helpers ─────────────────────────────────────────────

    private Map<String, Object> toMap(McpServerConfig c) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", c.name());
        map.put("transport", c.transport().name());
        if (c.url() != null) map.put("url", c.url());
        if (c.command() != null) map.put("command", c.command());
        if (!c.args().isEmpty()) map.put("args", c.args());
        if (!c.env().isEmpty()) map.put("env", c.env());
        if (c.authToken() != null && !c.authToken().isBlank()) map.put("authToken", "***");
        map.put("enabled", c.enabled());
        return map;
    }

    private static String nullableText(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    private static List<String> toList(com.fasterxml.jackson.databind.JsonNode node) {
        var list = new ArrayList<String>();
        for (var item : node) list.add(item.asText());
        return List.copyOf(list);
    }

    private static Map<String, String> toMap(com.fasterxml.jackson.databind.JsonNode node) {
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
