package com.clawcode.agent.tools.shell;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.tools.powershell")
@Validated
public record PowerShellToolProperties(
    @DefaultValue("30") @Min(1) long timeoutSeconds
) {}
