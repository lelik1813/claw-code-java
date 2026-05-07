package com.clawcode.agent.shared.message;

import java.time.Instant;
import java.util.UUID;

public sealed interface Message permits UserMessage, AssistantMessage, ToolResultMessage {
    UUID uuid();

    Instant createdAt();
}
