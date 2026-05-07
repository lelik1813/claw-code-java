package com.clawcode.agent.shared.message;

public record AssistantThinkingBlock(String thinking, String signature) implements AssistantContentBlock {
    public AssistantThinkingBlock {
        if (thinking == null) {
            thinking = "";
        }
        if (signature != null && signature.isBlank()) {
            signature = null;
        }
    }
}
