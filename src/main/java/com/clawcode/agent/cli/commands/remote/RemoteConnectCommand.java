package com.clawcode.agent.cli.commands.remote;

import java.net.URI;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.remote.RemoteConnection;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "connect",
    description = "Connect to a remote claw-code-java endpoint.",
    mixinStandardHelpOptions = true)
public class RemoteConnectCommand implements Callable<Integer> {

    @ParentCommand
    RemoteControlCommand parent;

    @Option(names = {"--endpoint"}, description = "Remote endpoint URL (e.g. https://remote.example.com:8080)",
        required = true)
    String endpoint;

    @Option(names = {"--session"}, description = "Existing session ID to attach to")
    String sessionId;

    @Override
    public Integer call() {
        var out = parent.out();
        var store = parent.store();

        // Validate endpoint
        if (!isValidEndpoint(endpoint)) {
            out.println("Error: invalid endpoint URL — must be http:// or https:// with a host");
            return AgentCliApplication.EXIT_USAGE_ERROR;
        }

        // Check auth prerequisites
        var auth = parent.authStore().load();
        if (auth.isEmpty()) {
            out.println("Error: not authenticated — run 'auth login' first");
            return AgentCliApplication.EXIT_API_ERROR;
        }

        // Check if already connected to same endpoint
        var existing = store.load();
        if (existing.isPresent() && existing.get().isConnected()) {
            if (existing.get().endpoint().equals(endpoint)) {
                out.printf("Already connected to %s%n", endpoint);
                return AgentCliApplication.EXIT_OK;
            }
            out.printf("Disconnecting from %s%n", existing.get().endpoint());
        }

        // Save connection
        var conn = new RemoteConnection(
            endpoint,
            sessionId,
            RemoteConnection.STATUS_CONNECTED,
            System.currentTimeMillis()
        );
        store.save(conn);

        out.printf("Connected to %s%n", endpoint);
        if (sessionId != null) {
            out.printf("  session: %s%n", sessionId);
        }
        return AgentCliApplication.EXIT_OK;
    }

    static boolean isValidEndpoint(String url) {
        try {
            var uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                return false;
            }
            String host = uri.getHost();
            return host != null && !host.isBlank();
        } catch (Exception e) {
            return false;
        }
    }
}
