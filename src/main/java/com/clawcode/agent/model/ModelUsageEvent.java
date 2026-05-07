package com.clawcode.agent.model;

public record ModelUsageEvent(Long inputTokens, Long outputTokens) implements ModelEvent {

    public ModelUsageEvent() {
        this(null, null);
    }
}
