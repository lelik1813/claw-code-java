package com.clawcode.agent.cli.daemon;

import java.util.List;
import java.util.Map;

public record DaemonLaunchRequest(
    String jar,
    int port,
    Map<String, String> environment,
    List<String> appArgs
) {
    public DaemonLaunchRequest {
        if (jar == null || jar.isBlank()) throw new IllegalArgumentException("jar required");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("port out of range");
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        appArgs = appArgs == null ? List.of() : List.copyOf(appArgs);
    }
}
