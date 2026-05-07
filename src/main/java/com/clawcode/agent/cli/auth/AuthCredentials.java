package com.clawcode.agent.cli.auth;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed CLI-side model for stored authentication credentials.
 */
public record AuthCredentials(
    String apiKey,
    String apiKeyHeader,
    Map<String, String> customHeaders,
    Instant updatedAt
) {

    public AuthCredentials {
        if (apiKeyHeader == null || apiKeyHeader.isBlank()) {
            apiKeyHeader = "X-API-Key";
        }
        if (customHeaders == null) {
            customHeaders = Map.of();
        }
    }

    public String maskedApiKey() {
        if (apiKey == null || apiKey.isEmpty()) return "";
        if (apiKey.length() <= 8) return "***";
        return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }

    public Map<String, String> maskedCustomHeaders() {
        var masked = new LinkedHashMap<String, String>();
        for (var entry : customHeaders.entrySet()) {
            String v = entry.getValue();
            if (v != null && v.length() > 8) {
                masked.put(entry.getKey(), v.substring(0, 4) + "..." + v.substring(v.length() - 4));
            } else {
                masked.put(entry.getKey(), "***");
            }
        }
        return Map.copyOf(masked);
    }

    public static final class ValidationException extends RuntimeException {
        public ValidationException(String message) { super(message); }
    }
}
