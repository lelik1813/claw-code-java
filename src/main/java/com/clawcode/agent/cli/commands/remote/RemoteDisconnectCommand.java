package com.clawcode.agent.cli.commands.remote;

import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.remote.RemoteConnection;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(name = "disconnect",
    description = "Disconnect from the remote endpoint.",
    mixinStandardHelpOptions = true)
public class RemoteDisconnectCommand implements Callable<Integer> {

    @ParentCommand
    RemoteControlCommand parent;

    @Override
    public Integer call() {
        var out = parent.out();
        var store = parent.store();

        var existing = store.load();
        if (existing.isEmpty()) {
            out.println("Remote: not connected");
            return AgentCliApplication.EXIT_OK;
        }

        var conn = existing.get();
        if (!conn.isConnected()) {
            out.printf("Remote: already disconnected (endpoint: %s)%n", conn.endpoint());
            return AgentCliApplication.EXIT_OK;
        }

        // Save as disconnected, keeping endpoint for reference
        var disconnected = new RemoteConnection(
            conn.endpoint(),
            null,
            RemoteConnection.STATUS_DISCONNECTED,
            conn.connectedAt()
        );
        store.save(disconnected);

        out.printf("Disconnected from %s%n", conn.endpoint());
        return AgentCliApplication.EXIT_OK;
    }
}
