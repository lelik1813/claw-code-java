package com.clawcode.agent.cli.commands.auth;

import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.commands.AuthCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "status",
    description = "Show current authentication status.",
    mixinStandardHelpOptions = true)
public class AuthStatusCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    AuthCommand parent;

    @Override
    public Integer call() {
        var out = parent.out();
        var creds = parent.store().load();
        if (creds.isEmpty()) {
            out.println("Not authenticated");
            out.println("Run: agent-cli auth login --api-key <KEY>");
            return AgentCliApplication.EXIT_OK;
        }
        var c = creds.get();
        out.println("Authenticated");
        out.printf("  header : %s%n", c.apiKeyHeader());
        out.printf("  key    : %s%n", c.maskedApiKey());
        if (!c.customHeaders().isEmpty()) {
            var masked = c.maskedCustomHeaders();
            out.printf("  custom headers:%n");
            for (var entry : masked.entrySet()) {
                out.printf("    %s: %s%n", entry.getKey(), entry.getValue());
            }
        }
        if (c.updatedAt() != null) {
            out.printf("  updated: %s%n", c.updatedAt());
        }
        out.printf("  config : %s%n", parent.store().configPath());
        return AgentCliApplication.EXIT_OK;
    }
}
