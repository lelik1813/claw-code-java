package com.clawcode.agent.cli.daemon;

import java.time.Duration;

public interface DaemonHealthChecker {

    boolean waitUntilReady(int port, Duration timeout);

    boolean isHealthy(int port, Duration requestTimeout);
}
