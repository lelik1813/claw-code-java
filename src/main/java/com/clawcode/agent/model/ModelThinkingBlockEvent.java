package com.clawcode.agent.model;

import com.clawcode.agent.shared.message.AssistantThinkingBlock;

public record ModelThinkingBlockEvent(AssistantThinkingBlock block) implements ModelEvent {
}
