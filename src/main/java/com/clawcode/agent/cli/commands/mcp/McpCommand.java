package com.clawcode.agent.cli.commands.mcp;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.mcp.FileMcpConfigStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "mcp",
    description = "Manage MCP server connections.%n  Use: agent-cli mcp list | add | remove | test | enable | disable",
    mixinStandardHelpOptions = true,
    subcommands = {
        McpListCommand.class,
        McpAddCommand.class,
        McpRemoveCommand.class,
        McpTestCommand.class,
        McpEnableCommand.class,
        McpDisableCommand.class
    })
public class McpCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    AgentCliApplication parent;

    @Spec
    CommandSpec spec;

    public FileMcpConfigStore store;

    public FileMcpConfigStore store() {
        if (store == null) {
            store = new FileMcpConfigStore();
        }
        return store;
    }

    public PrintWriter out() {
        if (parent != null) return parent.out();
        return spec.commandLine().getOut();
    }

    @Override
    public Integer call() {
        new CommandLine(this).usage(out());
        return AgentCliApplication.EXIT_OK;
    }
}
