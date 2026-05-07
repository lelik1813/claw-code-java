package com.clawcode.agent.mcp;

public class McpRemoteException extends RuntimeException {

    private final String serverName;

    public McpRemoteException(String serverName, String message, Throwable cause) {
        super("MCP server '" + serverName + "': " + message, cause);
        this.serverName = serverName;
    }

    public String serverName() {
        return serverName;
    }
}
