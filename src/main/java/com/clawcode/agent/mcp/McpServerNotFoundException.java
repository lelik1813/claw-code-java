package com.clawcode.agent.mcp;

public class McpServerNotFoundException extends RuntimeException {

    private final String serverName;

    public McpServerNotFoundException(String serverName) {
        super("MCP server not found: " + serverName);
        this.serverName = serverName;
    }

    public String serverName() {
        return serverName;
    }
}
