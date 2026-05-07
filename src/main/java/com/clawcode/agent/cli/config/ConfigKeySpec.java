package com.clawcode.agent.cli.config;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry of valid config keys with their types, defaults, and validation rules.
 */
public final class ConfigKeySpec {

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();
    static {
        add("baseUrl", Type.URL, "http://localhost:8080");
        add("apiKeyHeader", Type.STRING, "X-API-Key");
        add("timeoutMs", Type.POSITIVE_LONG, "30000");
        add("streamReadTimeoutMs", Type.POSITIVE_LONG, "300000");
        add("serverPort", Type.PORT, "8080");
        add("serverJar", Type.STRING, "auto");
        add("persistenceBackend", Type.PERSISTENCE_BACKEND, "in-memory");
    }

    public enum Type {
        STRING, URL, POSITIVE_LONG, PORT, PERSISTENCE_BACKEND
    }

    record Spec(Type type, String defaultValue) {}

    private static void add(String key, Type type, String defaultValue) {
        SPECS.put(key, new Spec(type, defaultValue));
    }

    public static Set<String> knownKeys() {
        return Set.copyOf(SPECS.keySet());
    }

    public static boolean isKnown(String key) {
        return SPECS.containsKey(key);
    }

    public static String defaultValue(String key) {
        var spec = SPECS.get(key);
        return spec != null ? spec.defaultValue() : null;
    }

    public static Type typeOf(String key) {
        var spec = SPECS.get(key);
        return spec != null ? spec.type() : null;
    }

    public static void validate(String key, String value) {
        if (!isKnown(key)) {
            throw new ValidationException("unknown config key: '" + key
                + "'. Known keys: " + String.join(", ", SPECS.keySet()));
        }
        if (value == null || value.isBlank()) {
            throw new ValidationException("value must not be blank");
        }
        var spec = SPECS.get(key);
        switch (spec.type()) {
            case URL -> validateUrl(value);
            case POSITIVE_LONG -> validatePositiveLong(value);
            case PORT -> validatePort(value);
            case PERSISTENCE_BACKEND -> validatePersistenceBackend(value);
            case STRING -> {} // any non-blank string is ok
        }
    }

    private static void validateUrl(String value) {
        try {
            var uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new ValidationException(
                    "invalid URL: must include scheme and host: " + value);
            }
        } catch (IllegalArgumentException e) {
            throw new ValidationException("invalid URL: " + value);
        }
    }

    private static void validatePositiveLong(String value) {
        long parsed;
        try {
            parsed = Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            throw new ValidationException("invalid number: " + value);
        }
        if (parsed <= 0) {
            throw new ValidationException("value must be positive: " + value);
        }
    }

    private static void validatePort(String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new ValidationException("invalid port: " + value);
        }
        if (parsed <= 0 || parsed > 65535) {
            throw new ValidationException("port out of range: " + value);
        }
    }

    private static void validatePersistenceBackend(String value) {
        String normalized = value.strip().toLowerCase();
        if (!normalized.equals("in-memory") && !normalized.equals("postgres")) {
            throw new ValidationException(
                "persistenceBackend must be 'in-memory' or 'postgres'");
        }
    }

    public static final class ValidationException extends RuntimeException {
        public ValidationException(String message) { super(message); }
    }

    private ConfigKeySpec() {}
}
