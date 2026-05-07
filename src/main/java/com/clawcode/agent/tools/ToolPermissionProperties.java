package com.clawcode.agent.tools;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.tools")
public record ToolPermissionProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("ALLOWLIST") Mode mode,
    Set<String> allowedTools
) {

    public enum Mode {
        ALLOWLIST,
        DENYLIST
    }

    public ToolPermissionProperties(boolean enabled, Mode mode, Set<String> allowedTools) {
        this.enabled = enabled;
        this.mode = mode != null ? mode : Mode.ALLOWLIST;
        this.allowedTools = allowedTools != null ? allowedTools : Set.of();
    }
}
