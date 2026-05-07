package com.clawcode.agent.shared.message;

import java.time.Instant;
import java.util.UUID;

public record UserMessage(
    UUID uuid,
    Instant createdAt,
    String content
) implements Message {
}
