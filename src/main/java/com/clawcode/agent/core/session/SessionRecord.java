package com.clawcode.agent.core.session;

import java.time.Instant;

public record SessionRecord(
    String sessionId,
    Instant createdAt
) {
}
