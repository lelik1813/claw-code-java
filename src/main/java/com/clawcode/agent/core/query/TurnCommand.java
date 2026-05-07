package com.clawcode.agent.core.query;

import com.clawcode.agent.shared.message.Message;
import java.util.List;

public record TurnCommand(
    String turnId,
    String sessionId,
    List<Message> messages,
    String model,
    String systemPrompt,
    List<String> skillIds,
    int persistFromIndex
) {

    public TurnCommand {
        skillIds = skillIds != null ? skillIds : List.of();
    }

    public TurnCommand(String turnId, String sessionId, List<Message> messages,
                       String model, String systemPrompt, List<String> skillIds) {
        this(turnId, sessionId, messages, model, systemPrompt, skillIds,
            messages == null ? 0 : messages.size());
    }
}
