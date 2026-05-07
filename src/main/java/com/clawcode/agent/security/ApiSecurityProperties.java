package com.clawcode.agent.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.security")
public record ApiSecurityProperties(
    ApiKey apiKey,
    RateLimit rateLimit,
    @DefaultValue("/actuator/**,/public/**") List<String> publicPaths
) {

    public ApiSecurityProperties {
        if (apiKey == null) apiKey = new ApiKey(false, "X-API-Key", null);
        if (rateLimit == null) rateLimit = new RateLimit(false, 100, 60);
        if (publicPaths == null) publicPaths = List.of("/actuator/**");
    }

    public record ApiKey(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("X-API-Key") String header,
        String key
    ) {
        public ApiKey {
            if (header == null || header.isBlank()) header = "X-API-Key";
        }
    }

    public record RateLimit(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("100") int requests,
        @DefaultValue("60") int windowSeconds
    ) {}
}
