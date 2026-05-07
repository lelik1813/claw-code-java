package com.clawcode.agent.model;

import java.util.Map;

public record ModelToolDefinition(
    String name,
    String description,
    Map<String, Object> inputSchema
) {
}
