package com.clawcode.agent.mcp;

import java.net.URI;

public record McpResourceContent(
    URI uri,
    String mimeType,
    String text
) {}
