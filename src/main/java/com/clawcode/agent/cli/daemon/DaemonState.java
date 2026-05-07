package com.clawcode.agent.cli.daemon;

/**
 * Immutable snapshot of daemon process state.
 */
public record DaemonState(
    long pid,
    int port,
    long startedAt,
    String status
) {

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_STOPPED = "stopped";

    public DaemonState {
        if (pid <= 0) throw new IllegalArgumentException("pid must be positive");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("port out of range");
        if (startedAt <= 0) throw new IllegalArgumentException("startedAt must be positive");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status required");
    }

    public boolean isRunning() {
        return STATUS_RUNNING.equals(status);
    }
}
