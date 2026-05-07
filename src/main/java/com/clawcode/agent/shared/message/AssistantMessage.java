package com.clawcode.agent.shared.message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AssistantMessage(
    UUID uuid,
    Instant createdAt,
    List<AssistantContentBlock> content
) implements Message {

    public AssistantMessage(UUID uuid, Instant createdAt, String text) {
        this(uuid, createdAt, List.of(new AssistantTextBlock(text)));
    }

    public String textContent() {
        return content.stream()
            .filter(AssistantTextBlock.class::isInstance)
            .map(AssistantTextBlock.class::cast)
            .map(AssistantTextBlock::text)
            .collect(java.util.stream.Collectors.joining());
    }
}
