package com.clawcode.agent.forensics;

import java.time.Instant;
import java.util.Map;

public record AuditEvent(
    String eventType,
    String sessionId,
    String turnId,
    Instant timestamp,
    Map<String, Object> attributes
) {

    public AuditEvent {
        attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
    }

    public static AuditEvent of(String eventType, String sessionId, String turnId, Map<String, Object> attributes) {
        return new AuditEvent(eventType, sessionId, turnId, Instant.now(), attributes);
    }
}
