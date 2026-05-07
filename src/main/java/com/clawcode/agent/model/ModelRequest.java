package com.clawcode.agent.model;

import com.clawcode.agent.shared.message.Message;
import java.util.List;

public record ModelRequest(
    String turnId,
    List<Message> messages,
    String model,
    String systemPrompt,
    List<ModelToolDefinition> tools
) {

    public ModelRequest(String turnId, List<Message> messages, String model, String systemPrompt) {
        this(turnId, messages, model, systemPrompt, List.of());
    }
}
