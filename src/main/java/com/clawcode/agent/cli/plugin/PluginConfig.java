package com.clawcode.agent.cli.plugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Typed CLI-side model for a plugin configuration.
 * Centralizes validation so all plugin subcommands share one contract.
 */
public record PluginConfig(
    String name,
    String id,
    PluginSource source,
    String version,
    boolean enabled,
    Instant installedAt,
    String pathOrUrl
) {

    private static final Pattern VALID_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]*(\\.[a-zA-Z][a-zA-Z0-9_-]*)*");
    private static final Pattern VALID_ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]*");

    public enum PluginSource {
        PATH, URL, REGISTRY;

        public static PluginSource parse(String value) {
            if (value == null) throw new ValidationException("source is required");
            return switch (value.toUpperCase()) {
                case "PATH" -> PATH;
                case "URL" -> URL;
                case "REGISTRY" -> REGISTRY;
                default -> throw new ValidationException(
                    "invalid source: " + value + ". Must be one of: PATH, URL, REGISTRY");
            };
        }
    }

    public static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("plugin name must not be blank");
        }
        if (!VALID_NAME.matcher(name).matches()) {
            throw new ValidationException(
                "invalid plugin name: " + name + ". Must start with a letter, only letters, digits, _, - or dot-separated segments");
        }
    }

    public static void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new ValidationException("plugin id must not be blank");
        }
        if (!VALID_ID.matcher(id).matches()) {
            throw new ValidationException(
                "invalid plugin id: " + id + ". Must start with a letter or digit, only letters, digits, _, . or -");
        }
    }

    public static void validate(PluginConfig config) {
        var errors = new ArrayList<String>();
        try { validateName(config.name()); } catch (ValidationException e) { errors.add(e.getMessage()); }
        try { validateId(config.id()); } catch (ValidationException e) { errors.add(e.getMessage()); }

        if (config.source() == null) {
            errors.add("source is required");
        } else {
            switch (config.source()) {
                case PATH, URL -> {
                    if (config.pathOrUrl() == null || config.pathOrUrl().isBlank()) {
                        errors.add("pathOrUrl is required for " + config.source() + " source");
                    }
                }
                case REGISTRY -> {
                    // registry resolves by name, pathOrUrl optional
                }
            }
        }

        if (config.version() != null && config.version().isBlank()) {
            errors.add("version must not be blank if provided");
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(String.join("; ", errors));
        }
    }

    public static final class ValidationException extends RuntimeException {
        public ValidationException(String message) { super(message); }
    }
}
