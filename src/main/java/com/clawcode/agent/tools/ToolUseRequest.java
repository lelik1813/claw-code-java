package com.clawcode.agent.tools;

public record ToolUseRequest(String toolCallId, String toolName, Object input) {
}
