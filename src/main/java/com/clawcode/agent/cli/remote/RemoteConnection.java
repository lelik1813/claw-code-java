package com.clawcode.agent.cli.remote;

/**
 * Immutable snapshot of remote-control connection state.
 */
public record RemoteConnection(
    String endpoint,
    String sessionId,
    String status,
    long connectedAt
) {

    public static final String STATUS_CONNECTED = "connected";
    public static final String STATUS_DISCONNECTED = "disconnected";

    public RemoteConnection {
        if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("endpoint required");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status required");
    }

    public boolean isConnected() {
        return STATUS_CONNECTED.equals(status);
    }
}
