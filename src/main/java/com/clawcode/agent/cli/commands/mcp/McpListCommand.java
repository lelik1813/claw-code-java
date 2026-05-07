package com.clawcode.agent.cli.commands.mcp;

import java.util.concurrent.Callable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.clawcode.agent.cli.AgentCliApplication;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "list",
    description = "List configured MCP servers.",
    mixinStandardHelpOptions = true)
public class McpListCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    McpCommand parent;

    @Option(names = {"--json"}, description = "Output as JSON")
    boolean json;

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public Integer call() {
        var out = parent.out();
        var servers = parent.store().load();
        if (servers.isEmpty()) {
            out.println("(no mcp servers)");
            return AgentCliApplication.EXIT_OK;
        }
        if (json) {
            try {
                out.println(JSON_MAPPER.writeValueAsString(servers));
            } catch (Exception e) {
                out.println("Error serializing servers: " + e.getMessage());
                return AgentCliApplication.EXIT_API_ERROR;
            }
            return AgentCliApplication.EXIT_OK;
        }
        out.printf("%-20s %-10s %-40s %-7s%n", "NAME", "TRANSPORT", "TARGET", "ENABLED");
        for (var s : servers) {
            String target = s.url() != null ? s.url() : s.command() != null ? s.command() : "-";
            out.printf("%-20s %-10s %-40s %-7s%n",
                s.name(), s.transport(), target, s.enabled() ? "yes" : "no");
        }
        return AgentCliApplication.EXIT_OK;
    }
}
