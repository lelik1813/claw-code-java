package com.clawcode.agent.tools.web;

import com.clawcode.agent.tools.Tool;
import com.clawcode.agent.tools.ToolDefinition;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

public class WebSearchTool implements Tool {

    static final int DEFAULT_LIMIT = 10;

    private final WebSearchClient client;

    public WebSearchTool(WebSearchClient client) {
        this.client = client;
    }

    private static final ToolDefinition DEFINITION = new ToolDefinition(
        "web_search",
        "Search the web and return a list of results. "
            + "Each result contains 'title', 'url', and 'snippet' fields.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "query", Map.of("type", "string",
                    "description", "Search query string."),
                "limit", Map.of("type", "integer",
                    "description", "Maximum number of results to return. Defaults to 10.")
            ),
            "required", List.of("query"),
            "additionalProperties", false
        )
    );

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Mono<Object> execute(Object input, Object context) {
        String query = extractQuery(input);
        if (query == null || query.isBlank()) {
            return Mono.error(new IllegalArgumentException("query must not be empty"));
        }
        int limit = extractLimit(input);
        return client.search(query, limit)
            .map(this::toMap)
            .collectList()
            .map(results -> results);
    }

    private String extractQuery(Object input) {
        if (input instanceof Map<?, ?> map) {
            Object q = map.get("query");
            return q != null ? q.toString() : null;
        }
        return input != null ? input.toString() : null;
    }

    private int extractLimit(Object input) {
        if (input instanceof Map<?, ?> map) {
            Object limit = map.get("limit");
            if (limit instanceof Number n) return n.intValue();
            if (limit instanceof String s) {
                try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
            }
        }
        return DEFAULT_LIMIT;
    }

    private Map<String, String> toMap(SearchResultItem item) {
        return Map.of(
            "title", item.title() != null ? item.title() : "",
            "url", item.url(),
            "snippet", item.snippet() != null ? item.snippet() : ""
        );
    }
}
