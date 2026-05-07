package com.clawcode.agent.cli.daemon;

import java.util.Optional;

/**
 * Abstraction over process lifecycle checks.
 * Default implementation uses {@link ProcessHandle}; tests can inject a mock.
 */
public class ProcessHandleProvider {

    public boolean isAlive(long pid) {
        return ProcessHandle.of(pid)
            .map(ProcessHandle::isAlive)
            .orElse(false);
    }

    public Optional<Boolean> destroy(long pid) {
        return ProcessHandle.of(pid).map(ph -> {
            ph.destroy();
            return true;
        });
    }
}
