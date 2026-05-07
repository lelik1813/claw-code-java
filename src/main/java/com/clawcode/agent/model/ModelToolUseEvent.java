package com.clawcode.agent.model;

public record ModelToolUseEvent(String toolCallId, String toolName, Object input, int contentIndex) implements ModelEvent {

    public ModelToolUseEvent(String toolCallId, String toolName, Object input) {
        this(toolCallId, toolName, input, -1);
    }
}
