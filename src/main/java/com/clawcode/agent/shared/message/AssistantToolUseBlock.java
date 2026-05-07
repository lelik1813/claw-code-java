package com.clawcode.agent.shared.message;

import java.util.Map;

public record AssistantToolUseBlock(String id, String name, Object input) implements AssistantContentBlock {
    public AssistantToolUseBlock {
        if (input == null) {
            input = Map.of();
        }
    }
}
