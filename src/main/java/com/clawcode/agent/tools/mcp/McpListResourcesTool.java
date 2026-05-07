package com.clawcode.agent.tools.mcp;

import com.clawcode.agent.mcp.McpResource;
import com.clawcode.agent.mcp.McpService;
import com.clawcode.agent.tools.Tool;
import com.clawcode.agent.tools.ToolDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnBean(McpService.class)
public class McpListResourcesTool implements Tool {

    private final McpService mcpService;

    public McpListResourcesTool(McpService mcpService) {
        this.mcpService = mcpService;
    }

    private static final ToolDefinition DEFINITION = new ToolDefinition(
        "mcp_list_resources",
        "List resources available on a connected MCP (Model Context Protocol) server. "
            + "Returns an array of objects with 'uri', 'name', 'description', and 'mimeType' fields.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "server", Map.of("type", "string",
                    "description", "Name of the MCP server to query.")
            ),
            "required", List.of("server"),
            "additionalProperties", false
        )
    );

    @Override
    public String name() {
        return "mcp_list_resources";
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Mono<Object> execute(Object input, Object context) {
        String server = extractServer(input);
        return mcpService.listResources(server)
            .map(McpListResourcesTool::toMap)
            .collectList()
            .map(list -> list);
    }

    private String extractServer(Object input) {
        if (input instanceof String s) return s;
        if (input instanceof Map<?, ?> m) {
            Object val = m.get("server");
            return val != null ? val.toString() : null;
        }
        return null;
    }

    private static Map<String, Object> toMap(McpResource r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uri", r.uri().toString());
        map.put("name", r.name());
        if (r.description() != null && !r.description().isEmpty()) {
            map.put("description", r.description());
        }
        if (r.mimeType() != null) {
            map.put("mimeType", r.mimeType());
        }
        return map;
    }
}
