package com.clawcode.agent.api.dto;

import java.time.Instant;

public record SessionResponse(
    String sessionId,
    Instant createdAt
) {
}
