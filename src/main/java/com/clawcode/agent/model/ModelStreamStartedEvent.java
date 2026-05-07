package com.clawcode.agent.model;

public record ModelStreamStartedEvent(
    String model
) implements ModelEvent {
}
