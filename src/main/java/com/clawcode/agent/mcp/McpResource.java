package com.clawcode.agent.mcp;

import java.net.URI;

public record McpResource(
    URI uri,
    String name,
    String description,
    String mimeType
) {}
