package com.clawcode.agent.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(prefix = "app.plugins", name = "enabled", havingValue = "true")
public class FileSystemPluginRegistry implements PluginRegistry {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, PluginDescriptor> plugins;

    public FileSystemPluginRegistry(PluginsProperties properties) {
        this.plugins = loadFromDirectories(properties.directories());
    }

    @Override
    public Flux<PluginDescriptor> list() {
        return Flux.fromIterable(plugins.values());
    }

    @Override
    public Mono<PluginDescriptor> resolve(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return Mono.error(new IllegalArgumentException("pluginId is required"));
        }
        PluginDescriptor descriptor = plugins.get(pluginId);
        if (descriptor == null) {
            return Mono.error(new NoSuchFileException("Plugin not found: " + pluginId));
        }
        return Mono.just(descriptor);
    }

    private Map<String, PluginDescriptor> loadFromDirectories(java.util.List<String> directories) {
        Map<String, PluginDescriptor> result = new LinkedHashMap<>();
        for (String dir : directories) {
            Path dirPath = Path.of(dir).toAbsolutePath().normalize();
            if (!Files.isDirectory(dirPath)) {
                continue;
            }
            loadMarketplaceFile(dirPath.resolve("marketplace.json"), result);
        }
        return Map.copyOf(result);
    }

    private void loadMarketplaceFile(Path file, Map<String, PluginDescriptor> result) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String content = Files.readString(file);
            JsonNode root = objectMapper.readTree(content);
            JsonNode plugins = root.path("plugins");
            if (!plugins.isArray()) {
                return;
            }
            for (Iterator<JsonNode> it = plugins.elements(); it.hasNext();) {
                parsePlugin(it.next(), file, result);
            }
        } catch (IOException e) {
            throw new RuntimeException(
                "Failed to read plugin marketplace: " + file, e);
        }
    }

    private void parsePlugin(JsonNode node, Path source, Map<String, PluginDescriptor> result) {
        String id = requireText(node, "id", source);
        String name = node.path("name").asText(id);
        String version = node.path("version").asText(null);
        String locationStr = node.path("location").asText(null);

        URI location;
        try {
            location = locationStr != null && !locationStr.isBlank()
                ? URI.create(locationStr)
                : source.getParent().resolve(id).toUri();
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(
                "Invalid location for plugin '" + id + "' in " + source + ": " + locationStr, e);
        }

        List<PluginToolDescriptor> tools = parseTools(id, node.path("tools"), source);

        PluginDescriptor descriptor = new PluginDescriptor(id, name, version, location, tools);
        if (result.putIfAbsent(id, descriptor) != null) {
            throw new RuntimeException(
                "Duplicate plugin id '" + id + "' in " + source);
        }
    }

    private List<PluginToolDescriptor> parseTools(String pluginId, JsonNode toolsNode, Path source) {
        if (!toolsNode.isArray()) {
            return List.of();
        }
        List<PluginToolDescriptor> tools = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Iterator<JsonNode> it = toolsNode.elements(); it.hasNext();) {
            JsonNode toolNode = it.next();
            String toolName = toolNode.path("name").asText(null);
            if (toolName == null || toolName.isBlank()) {
                throw new RuntimeException(
                    "Plugin '" + pluginId + "' in " + source + " has a tool with missing or blank 'name'");
            }
            if (!seen.add(toolName)) {
                throw new RuntimeException(
                    "Plugin '" + pluginId + "' in " + source + " has duplicate tool name '" + toolName + "'");
            }
            String toolType = toolNode.path("type").asText("builtin");
            if (toolType.isBlank()) {
                toolType = "builtin";
            }
            Map<String, Object> config = parseToolConfig(pluginId, toolName, toolNode.path("config"), source);
            tools.add(new PluginToolDescriptor(toolName, toolType, config));
        }
        return tools;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseToolConfig(String pluginId, String toolName, JsonNode configNode, Path source) {
        if (configNode == null || configNode.isMissingNode() || configNode.isNull()) {
            return Map.of();
        }
        if (!configNode.isObject()) {
            throw new RuntimeException(
                "Plugin '" + pluginId + "' tool '" + toolName + "' in " + source
                + " has non-object 'config' field (expected JSON object)");
        }
        return objectMapper.convertValue(configNode, Map.class);
    }

    private String requireText(JsonNode node, String field, Path source) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new RuntimeException(
                "Missing required field '" + field + "' in plugin entry in " + source);
        }
        return value;
    }
}
