package com.clawcode.agent.core.query;

public record QueryTextDeltaEvent(
    String text
) implements QueryEvent {
}
