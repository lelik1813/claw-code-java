package com.clawcode.agent.shared.message;

import java.time.Instant;
import java.util.UUID;

public record ToolResultMessage(
    UUID uuid,
    Instant createdAt,
    String toolCallId,
    String toolName,
    String content,
    boolean isError
) implements Message {
}
