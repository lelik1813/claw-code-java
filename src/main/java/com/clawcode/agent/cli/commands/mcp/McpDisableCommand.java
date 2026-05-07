package com.clawcode.agent.cli.commands.mcp;

import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.mcp.McpServerConfig;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "disable",
    description = "Disable an enabled MCP server.",
    mixinStandardHelpOptions = true)
public class McpDisableCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    McpCommand parent;

    @Parameters(index = "0", paramLabel = "NAME", description = "Server name")
    String name;

    @Override
    public Integer call() {
        var out = parent.out();
        try {
            var updated = parent.store().updateEnabled(name, false);
            out.printf("Disabled MCP server '%s'%n", updated.name());
            return AgentCliApplication.EXIT_OK;
        } catch (McpServerConfig.ValidationException e) {
            out.println(e.getMessage());
            return AgentCliApplication.EXIT_API_ERROR;
        }
    }
}
