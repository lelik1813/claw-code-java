package com.clawcode.agent.cli.commands.mcp;

import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.mcp.McpServerConfig;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "remove",
    description = "Remove an MCP server configuration.",
    mixinStandardHelpOptions = true)
public class McpRemoveCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    McpCommand parent;

    @Parameters(index = "0", paramLabel = "NAME", description = "Server name to remove")
    String name;

    @Option(names = {"--force"}, description = "Suppress not-found error (exit 0)")
    boolean force;

    @Override
    public Integer call() {
        var out = parent.out();
        try {
            McpServerConfig.validateName(name);
            boolean removed = parent.store().remove(name);
            if (!removed) {
                if (force) {
                    return AgentCliApplication.EXIT_OK;
                }
                out.printf("Server '%s' not found in configuration.%n", name);
                return AgentCliApplication.EXIT_API_ERROR;
            }
            out.printf("Removed MCP server '%s'%n", name);
            return AgentCliApplication.EXIT_OK;
        } catch (McpServerConfig.ValidationException e) {
            out.println("Validation error: " + e.getMessage());
            return AgentCliApplication.EXIT_USAGE_ERROR;
        }
    }
}
