package com.clawcode.agent.core.query;

public record QueryUsageEvent(Long inputTokens, Long outputTokens) implements QueryEvent {

    public Long totalTokens() {
        return inputTokens != null && outputTokens != null ? inputTokens + outputTokens : null;
    }
}
