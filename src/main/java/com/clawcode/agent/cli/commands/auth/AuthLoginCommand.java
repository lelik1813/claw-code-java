package com.clawcode.agent.cli.commands.auth;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.auth.AuthCredentials;
import com.clawcode.agent.cli.commands.AuthCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "login",
    description = "Store API key and optional custom headers.",
    mixinStandardHelpOptions = true)
public class AuthLoginCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    AuthCommand parent;

    @Option(names = {"--api-key"}, description = "API key value", required = true)
    String apiKey;

    @Option(names = {"--api-key-header"}, description = "Header name for the API key (default: ${DEFAULT-VALUE})",
        defaultValue = "X-API-Key")
    String apiKeyHeader;

    @Option(names = {"--header"}, description = "Custom header KEY=VALUE (repeatable)")
    List<String> headerPairs;

    @Override
    public Integer call() {
        var out = parent.out();
        if (apiKey.isBlank()) {
            out.println("Validation error: --api-key must not be blank");
            return AgentCliApplication.EXIT_USAGE_ERROR;
        }
        try {
            Map<String, String> customHeaders = parseHeaders(headerPairs);
            var credentials = new AuthCredentials(
                apiKey.strip(), apiKeyHeader.strip(), customHeaders, Instant.now());
            parent.store().save(credentials);
            out.printf("Credentials saved to %s%n", parent.store().configPath());
            out.printf("  header : %s%n", credentials.apiKeyHeader());
            out.printf("  key    : %s%n", credentials.maskedApiKey());
            if (!customHeaders.isEmpty()) {
                out.printf("  headers: %s%n", customHeaders.keySet());
            }
            return AgentCliApplication.EXIT_OK;
        } catch (AuthCredentials.ValidationException e) {
            out.println("Validation error: " + e.getMessage());
            return AgentCliApplication.EXIT_USAGE_ERROR;
        }
    }

    static Map<String, String> parseHeaders(List<String> pairs) {
        if (pairs == null || pairs.isEmpty()) return Map.of();
        var map = new LinkedHashMap<String, String>();
        for (var pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new AuthCredentials.ValidationException(
                    "invalid --header format: '" + pair + "'. Expected KEY=VALUE");
            }
            map.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return Map.copyOf(map);
    }
}
