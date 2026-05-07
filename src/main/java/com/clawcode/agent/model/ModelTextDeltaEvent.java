package com.clawcode.agent.model;

public record ModelTextDeltaEvent(
    String text
) implements ModelEvent {
}
