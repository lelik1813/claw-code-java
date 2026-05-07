package com.clawcode.agent.core.query;

public record QueryErrorEvent(String message, String code, String source) implements QueryEvent {

    public QueryErrorEvent(String message, String source) {
        this(message, null, source);
    }
}
