package com.clawcode.agent.mcp;

public class McpDisabledException extends RuntimeException {

    public McpDisabledException() {
        super("MCP subsystem is not enabled (app.mcp.enabled=false)");
    }
}
