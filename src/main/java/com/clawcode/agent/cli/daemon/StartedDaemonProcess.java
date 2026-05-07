package com.clawcode.agent.cli.daemon;

public record StartedDaemonProcess(long pid, Runnable destroy) {

    public void destroyForcibly() {
        destroy.run();
    }
}
