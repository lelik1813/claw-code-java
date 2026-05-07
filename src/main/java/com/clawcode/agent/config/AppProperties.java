package com.clawcode.agent.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.tools")
@Validated
public record AppProperties(
    @DefaultValue("4") @Min(1) int maxToolConcurrency,
    @DefaultValue("12000") @Min(1000) int maxToolResultChars,
    @DefaultValue("4000") @Min(200) int toolResultExcerptChars,
    @DefaultValue("4") @Min(2) int toolSummaryMinCalls
) {}
