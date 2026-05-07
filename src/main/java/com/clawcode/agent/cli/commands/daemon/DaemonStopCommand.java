package com.clawcode.agent.cli.commands.daemon;

import java.util.Optional;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(name = "stop",
    description = "Stop the running daemon process.",
    mixinStandardHelpOptions = true)
public class DaemonStopCommand implements Callable<Integer> {

    @ParentCommand
    DaemonCommand parent;

    @Override
    public Integer call() {
        var out = parent.out();
        var store = parent.store();
        var processProvider = parent.processProvider;

        var existing = store.load();
        if (existing.isEmpty()) {
            out.println("Daemon: not running");
            return AgentCliApplication.EXIT_OK;
        }

        var state = existing.get();
        boolean alive = processProvider.isAlive(state.pid());

        if (alive) {
            Optional<Boolean> destroyed = processProvider.destroy(state.pid());
            if (destroyed.isPresent()) {
                out.printf("Daemon stopped (pid=%d)%n", state.pid());
            } else {
                out.printf("Warning: could not stop pid=%d — process may have already exited%n", state.pid());
            }
        } else {
            out.printf("Daemon was not running (stale pid=%d)%n", state.pid());
        }

        store.clear();
        return AgentCliApplication.EXIT_OK;
    }
}
