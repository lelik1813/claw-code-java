package com.clawcode.agent.api.dto;

import java.time.Instant;

public record CreateSessionResponse(
    String sessionId,
    Instant createdAt
) {
}
