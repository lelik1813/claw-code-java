package com.clawcode.agent.shared.message;

public sealed interface AssistantContentBlock permits AssistantTextBlock, AssistantThinkingBlock, AssistantToolUseBlock {
}
