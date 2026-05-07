package com.clawcode.agent.core.query;

public record QueryToolResultEvent(String toolCallId, String toolName, boolean isError, String summary) implements QueryEvent {
}
