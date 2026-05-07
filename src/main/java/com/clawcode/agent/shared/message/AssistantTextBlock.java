package com.clawcode.agent.shared.message;

public record AssistantTextBlock(String text) implements AssistantContentBlock {
    public AssistantTextBlock {
        if (text == null) {
            text = "";
        }
    }
}
