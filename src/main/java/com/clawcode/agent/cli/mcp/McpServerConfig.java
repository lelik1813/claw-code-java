package com.clawcode.agent.cli.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Typed CLI-side model for an MCP server configuration.
 * Centralizes validation so all mcp subcommands share one contract.
 */
public record McpServerConfig(
    String name,
    McpTransport transport,
    String url,
    String command,
    List<String> args,
    Map<String, String> env,
    String authToken,
    boolean enabled
) {

    private static final Pattern VALID_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]*");

    public McpServerConfig {
        if (args == null) args = List.of();
        if (env == null) env = Map.of();
    }

    public enum McpTransport {
        HTTP, STDIO, SSE;

        public static McpTransport parse(String value) {
            if (value == null) throw new ValidationException("transport is required");
            return switch (value.toUpperCase()) {
                case "HTTP" -> HTTP;
                case "STDIO" -> STDIO;
                case "SSE" -> SSE;
                default -> throw new ValidationException(
                    "invalid transport: " + value + ". Must be one of: HTTP, STDIO, SSE");
            };
        }
    }

    public static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("server name must not be blank");
        }
        if (!VALID_NAME.matcher(name).matches()) {
            throw new ValidationException(
                "invalid server name: " + name + ". Must start with a letter, only letters, digits, _ or -");
        }
    }

    public static void validate(McpServerConfig config) {
        var errors = new ArrayList<String>();
        try { validateName(config.name()); } catch (ValidationException e) { errors.add(e.getMessage()); }

        if (config.transport() == null) {
            errors.add("transport is required");
        } else {
            switch (config.transport()) {
                case HTTP, SSE -> {
                    if (config.url() == null || config.url().isBlank()) {
                        errors.add("url is required for " + config.transport() + " transport");
                    }
                }
                case STDIO -> {
                    if (config.command() == null || config.command().isBlank()) {
                        errors.add("command is required for STDIO transport");
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(String.join("; ", errors));
        }
    }

    public static final class ValidationException extends RuntimeException {
        public ValidationException(String message) { super(message); }
    }
}
