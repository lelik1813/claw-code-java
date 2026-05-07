package com.clawcode.agent.model;

public record ModelErrorEvent(String message, String providerCode) implements ModelEvent {

    public ModelErrorEvent(String message) {
        this(message, null);
    }
}
