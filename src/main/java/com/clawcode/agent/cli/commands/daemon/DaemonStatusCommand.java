package com.clawcode.agent.cli.commands.daemon;

import java.time.Duration;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(name = "status",
    description = "Report daemon status (running / stopped).",
    mixinStandardHelpOptions = true)
public class DaemonStatusCommand implements Callable<Integer> {

    @ParentCommand
    DaemonCommand parent;

    @Override
    public Integer call() {
        var out = parent.out();
        var store = parent.store();
        var processProvider = parent.processProvider;

        var existing = store.load();
        if (existing.isEmpty()) {
            out.println("Daemon: not started");
            return AgentCliApplication.EXIT_OK;
        }

        var state = existing.get();
        boolean alive = processProvider.isAlive(state.pid());

        if (alive && state.isRunning()) {
            long uptimeSec = (System.currentTimeMillis() - state.startedAt()) / 1000;
            boolean healthy = parent.healthChecker.isHealthy(state.port(), Duration.ofSeconds(2));
            out.printf("Daemon: running (pid=%d, port=%d, uptime=%ds, health=%s)%n",
                state.pid(), state.port(), uptimeSec, healthy ? "UP" : "UNHEALTHY");
            return AgentCliApplication.EXIT_OK;
        }

        // Process died but state remains — report stopped
        out.printf("Daemon: stopped (stale pid=%d, port=%d)%n", state.pid(), state.port());
        return AgentCliApplication.EXIT_OK;
    }
}
