package com.clawcode.agent.tools.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.web-tools")
public record WebToolsProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("true") boolean searchEnabled,
    @DefaultValue("true") boolean fetchEnabled,
    String searchBaseUrl,
    String searchApiKey,
    @DefaultValue("30000") int timeoutMs,
    @DefaultValue("1048576") int maxResponseBytes,
    @DefaultValue("50000") int maxTextChars,
    @DefaultValue("http,https") List<String> allowedSchemes,
    List<String> blockedHosts
) {

    public WebToolsProperties(boolean enabled, boolean searchEnabled, boolean fetchEnabled,
                              String searchBaseUrl, String searchApiKey, int timeoutMs,
                              int maxResponseBytes, int maxTextChars,
                              List<String> allowedSchemes, List<String> blockedHosts) {
        this.enabled = enabled;
        this.searchEnabled = searchEnabled;
        this.fetchEnabled = fetchEnabled;
        this.searchBaseUrl = searchBaseUrl;
        this.searchApiKey = searchApiKey;
        this.timeoutMs = timeoutMs;
        this.maxResponseBytes = maxResponseBytes;
        this.maxTextChars = maxTextChars;
        this.allowedSchemes = allowedSchemes != null ? allowedSchemes : List.of("http", "https");
        this.blockedHosts = blockedHosts != null ? blockedHosts : List.of();
    }
}
