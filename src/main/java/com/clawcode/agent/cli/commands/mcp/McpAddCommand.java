package com.clawcode.agent.cli.commands.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.mcp.McpServerConfig;
import com.clawcode.agent.cli.mcp.McpServerConfig.McpTransport;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "add",
    description = "Add an MCP server configuration.",
    mixinStandardHelpOptions = true)
public class McpAddCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    McpCommand parent;

    @Parameters(index = "0", paramLabel = "NAME", description = "Server name")
    String name;

    @Option(names = {"--type"}, description = "Transport type: HTTP, STDIO, SSE (default: ${DEFAULT-VALUE})",
        defaultValue = "HTTP")
    String type;

    @Option(names = {"--url"}, description = "Base URL (for HTTP/SSE transport)")
    String url;

    @Option(names = {"--command"}, description = "Command to launch (for STDIO transport)")
    String command;

    @Option(names = {"--arg"}, arity = "1", description = "Argument for STDIO command (repeatable)")
    List<String> args;

    @Option(names = {"--env"}, arity = "1", description = "Environment variable KEY=VALUE (repeatable)")
    List<String> envPairs;

    @Option(names = {"--auth-token"}, description = "Authentication token (for HTTP transport)")
    String authToken;

    @Option(names = {"--disabled"}, description = "Add server in disabled state")
    boolean disabled;

    @Override
    public Integer call() {
        var out = parent.out();
        try {
            McpTransport transport = McpTransport.parse(type);
            McpServerConfig.validateName(name);
            String normalizedUrl = normalizeUrl(url);
            Map<String, String> env = parseEnv(envPairs);
            McpServerConfig config = new McpServerConfig(
                name, transport, normalizedUrl, command,
                args != null ? List.copyOf(args) : List.of(),
                env,
                authToken, !disabled);
            McpServerConfig.validate(config);
            parent.store().add(config);
            out.printf("Added MCP server '%s' (type=%s) to %s%n", name, transport,
                parent.store().configPath());
            return AgentCliApplication.EXIT_OK;
        } catch (McpServerConfig.ValidationException e) {
            out.println("Validation error: " + e.getMessage());
            return AgentCliApplication.EXIT_USAGE_ERROR;
        }
    }

    static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) return url;
        String trimmed = url.stripTrailing();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static Map<String, String> parseEnv(List<String> pairs) {
        if (pairs == null || pairs.isEmpty()) return Map.of();
        var map = new LinkedHashMap<String, String>();
        for (var pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new McpServerConfig.ValidationException(
                    "invalid --env format: '" + pair + "'. Expected KEY=VALUE");
            }
            map.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return Map.copyOf(map);
    }
}
