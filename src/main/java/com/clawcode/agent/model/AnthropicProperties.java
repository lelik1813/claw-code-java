package com.clawcode.agent.model;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "anthropic")
public record AnthropicProperties(
    String authToken,
    String baseUrl,
    @Positive Integer timeoutMs,
    String defaultModel
) {

    public String baseUrl() {
        return baseUrl == null || baseUrl.isBlank()
            ? "https://api.deepseek.com/anthropic"
            : baseUrl;
    }

    public int effectiveTimeoutMs() {
        return timeoutMs == null || timeoutMs <= 0 ? 3000000 : timeoutMs;
    }

    public String defaultModel() {
        return defaultModel == null || defaultModel.isBlank()
            ? "deepseek-v4-flash"
            : defaultModel;
    }

    public boolean hasAuthToken() {
        return authToken != null && !authToken.isBlank();
    }
}
