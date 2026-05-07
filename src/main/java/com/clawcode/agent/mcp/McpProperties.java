package com.clawcode.agent.mcp;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.mcp")
public record McpProperties(
    @DefaultValue("false") boolean enabled,
    Map<String, McpServerDefinition> servers
) {

    public McpProperties(boolean enabled, Map<String, McpServerDefinition> servers) {
        this.enabled = enabled;
        this.servers = servers != null ? servers : Map.of();
    }

    public record McpServerDefinition(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("HTTP") McpTransportType type,
        // HTTP
        String baseUrl,
        String authToken,
        // STDIO
        String command,
        List<String> args,
        Map<String, String> env,
        String workingDir,
        @DefaultValue("10000") long startupTimeoutMs,
        // SSE
        Map<String, String> headers,
        @DefaultValue("30000") long connectTimeoutMs,
        @DefaultValue("300000") long readTimeoutMs
    ) {
        public McpServerDefinition {
            if (authToken == null) authToken = "";
            if (args == null) args = List.of();
            if (env == null) env = Map.of();
            if (headers == null) headers = Map.of();
        }

        public McpServerDefinition(boolean enabled, String baseUrl, String authToken) {
            this(enabled, McpTransportType.HTTP, baseUrl, authToken,
                null, null, null, null, 10_000, null, 30_000, 300_000);
        }
    }
}
