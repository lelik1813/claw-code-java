package com.clawcode.agent.cli;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.cli")
public record CliProperties(
    @DefaultValue("http://localhost:8080") String baseUrl,
    @DefaultValue("X-API-Key") String apiKeyHeader,
    String apiKey,
    @DefaultValue("30000") long timeoutMs,
    @DefaultValue("300000") long streamReadTimeoutMs
) {
    public CliProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8080";
        }
        try {
            var uri = URI.create(baseUrl);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException(
                    "app.cli.base-url must be a valid URL with scheme and host: " + baseUrl);
            }
        } catch (IllegalArgumentException e) {
            if (e.getMessage().startsWith("app.cli.base-url")) throw e;
            throw new IllegalArgumentException(
                "app.cli.base-url must be a valid URL: " + baseUrl, e);
        }
        if (apiKeyHeader == null || apiKeyHeader.isBlank()) {
            apiKeyHeader = "X-API-Key";
        }
        if (timeoutMs <= 0) timeoutMs = 30000;
        if (streamReadTimeoutMs <= 0) streamReadTimeoutMs = 300000;
    }
}
