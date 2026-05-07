package com.clawcode.agent.core.query;

public record QueryToolCallRequestedEvent(String toolCallId, String toolName, Object input) implements QueryEvent {

    public QueryToolCallRequestedEvent(String toolCallId, String toolName) {
        this(toolCallId, toolName, null);
    }
}
