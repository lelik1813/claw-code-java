package com.clawcode.agent.core.tasks;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.tasks")
public record TaskProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("IN_MEMORY") TaskBackend backend,
    @DefaultValue("10") int maxConcurrent,
    @DefaultValue("300000") long defaultTimeoutMs,
    Remote remote
) {

    public TaskProperties {
        if (remote == null) remote = new Remote(null, null, 30_000);
    }

    public enum TaskBackend {
        IN_MEMORY, REMOTE
    }

    public record Remote(
        String baseUrl,
        String authToken,
        @DefaultValue("30000") long timeoutMs
    ) {}
}
