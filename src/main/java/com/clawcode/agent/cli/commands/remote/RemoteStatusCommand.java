package com.clawcode.agent.cli.commands.remote;

import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(name = "status",
    description = "Show remote-control connection status.",
    mixinStandardHelpOptions = true)
public class RemoteStatusCommand implements Callable<Integer> {

    @ParentCommand
    RemoteControlCommand parent;

    @Override
    public Integer call() {
        var out = parent.out();
        var store = parent.store();

        var existing = store.load();
        if (existing.isEmpty()) {
            out.println("Remote: disconnected (no endpoint configured)");
            // Still check auth prerequisites
            var auth = parent.authStore().load();
            if (auth.isEmpty()) {
                out.println("  warning: not authenticated — run 'auth login' first");
            }
            return AgentCliApplication.EXIT_OK;
        }

        var conn = existing.get();
        if (conn.isConnected()) {
            out.printf("Remote: connected%n");
            out.printf("  endpoint: %s%n", conn.endpoint());
            if (conn.sessionId() != null) {
                out.printf("  session:  %s%n", conn.sessionId());
            }
            if (conn.connectedAt() > 0) {
                out.printf("  since:    %s%n", java.time.Instant.ofEpochMilli(conn.connectedAt()));
            }
        } else {
            out.printf("Remote: disconnected%n");
            out.printf("  endpoint: %s%n", conn.endpoint());
        }

        // Check prerequisites
        var auth = parent.authStore().load();
        if (auth.isEmpty()) {
            out.println("  warning: not authenticated — run 'auth login' first");
        }

        return AgentCliApplication.EXIT_OK;
    }
}
