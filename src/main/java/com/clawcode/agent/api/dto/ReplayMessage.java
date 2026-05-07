package com.clawcode.agent.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.ToolResultMessage;
import com.clawcode.agent.shared.message.UserMessage;
import java.time.Instant;
import java.util.Map;

public record ReplayMessage(
    @JsonProperty("role") String role,
    @JsonProperty("content") String content,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("metadata") Map<String, Object> metadata
) {

    public static ReplayMessage from(Message message) {
        return switch (message) {
            case UserMessage m -> new ReplayMessage("user", m.content(), m.createdAt(), null);
            case AssistantMessage m -> new ReplayMessage("assistant", m.textContent(), m.createdAt(), null);
            case ToolResultMessage m -> new ReplayMessage("tool", m.content(), m.createdAt(),
                Map.of("toolCallId", m.toolCallId(), "toolName", m.toolName(), "isError", m.isError()));
        };
    }
}
