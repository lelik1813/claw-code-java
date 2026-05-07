package com.clawcode.agent.tools;

/**
 * Single source of truth for tool permission decisions based on configured
 * allowlist/denylist rules. Used by both advertising (orchestrator) and
 * execution (permission policy) paths.
 */
public final class ToolPermissionRules {

    private ToolPermissionRules() {}

    /**
     * Returns {@code true} when the named tool is allowed under the given
     * properties. When permissions are disabled all tools are allowed.
     * ALLOWLIST mode permits only explicitly listed tools; DENYLIST mode
     * permits everything except explicitly listed tools.
     */
    public static boolean isAllowed(ToolPermissionProperties properties, String toolName) {
        if (!properties.enabled()) {
            return true;
        }
        boolean listed = properties.allowedTools().contains(toolName);
        return switch (properties.mode()) {
            case ALLOWLIST -> listed;
            case DENYLIST -> !listed;
        };
    }
}
