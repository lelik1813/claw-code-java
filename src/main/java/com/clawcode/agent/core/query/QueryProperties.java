package com.clawcode.agent.core.query;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.query")
@Validated
public record QueryProperties(
    @DefaultValue("10") @Min(1) int maxToolRounds,
    @DefaultValue("240000") @Min(10000) int maxModelRequestChars,
    @DefaultValue("true") boolean autoCompactEnabled,
    @DefaultValue("12") @Min(2) int compactPreserveRecentMessages,
    @DefaultValue("2") @Min(0) int maxOutputRecoveryAttempts
) {}
