package com.clawcode.agent.tools.mcp;

import com.clawcode.agent.mcp.McpResourceContent;
import com.clawcode.agent.mcp.McpService;
import com.clawcode.agent.tools.Tool;
import com.clawcode.agent.tools.ToolDefinition;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnBean(McpService.class)
public class McpReadResourceTool implements Tool {

    private final McpService mcpService;

    public McpReadResourceTool(McpService mcpService) {
        this.mcpService = mcpService;
    }

    private static final ToolDefinition DEFINITION = new ToolDefinition(
        "mcp_read_resource",
        "Read the content of a resource from a connected MCP server. "
            + "Use mcp_list_resources first to discover available URIs. "
            + "Returns an object with 'uri', 'mimeType', and 'text' fields.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "server", Map.of("type", "string",
                    "description", "Name of the MCP server that holds the resource."),
                "uri", Map.of("type", "string",
                    "description", "URI of the resource to read, as returned by mcp_list_resources.")
            ),
            "required", List.of("server", "uri"),
            "additionalProperties", false
        )
    );

    @Override
    public String name() {
        return "mcp_read_resource";
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Mono<Object> execute(Object input, Object context) {
        Map<String, String> params = extractParams(input);
        String server = params.get("server");
        String uriStr = params.get("uri");

        if (server == null || server.isBlank()) {
            return Mono.error(new IllegalArgumentException("server is required"));
        }
        if (uriStr == null || uriStr.isBlank()) {
            return Mono.error(new IllegalArgumentException("uri is required"));
        }

        URI uri;
        try {
            uri = URI.create(uriStr);
        } catch (IllegalArgumentException e) {
            return Mono.error(new IllegalArgumentException("invalid uri: " + uriStr, e));
        }

        return mcpService.readResource(server, uri)
            .map(McpReadResourceTool::toMap);
    }

    private Map<String, String> extractParams(Object input) {
        if (input instanceof Map<?, ?> m) {
            Map<String, String> result = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                if (v != null) result.put(k.toString(), v.toString());
            });
            return result;
        }
        return Map.of();
    }

    private static Map<String, Object> toMap(McpResourceContent c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uri", c.uri().toString());
        map.put("mimeType", c.mimeType());
        map.put("text", c.text());
        return map;
    }
}
