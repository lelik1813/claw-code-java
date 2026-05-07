package com.clawcode.agent.persistence.postgres;

import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.AssistantTextBlock;
import com.clawcode.agent.shared.message.AssistantThinkingBlock;
import com.clawcode.agent.shared.message.AssistantToolUseBlock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageRowTest {

    @Test
    void assistantToolUseAndThinkingBlocksRoundTripThroughContentColumn() {
        AssistantMessage message = new AssistantMessage(
            UUID.randomUUID(),
            Instant.now(),
            List.of(
                new AssistantThinkingBlock("internal", "sig"),
                new AssistantTextBlock("I will inspect."),
                new AssistantToolUseBlock("toolu_1", "file_read", Map.of("path", "README.md"))
            ));

        MessageRow row = MessageRow.from(UUID.randomUUID(), message, 1);
        AssistantMessage restored = (AssistantMessage) row.toMessage();

        assertThat(restored.content()).hasSize(3);
        assertThat(restored.content().get(0)).isInstanceOf(AssistantThinkingBlock.class);
        assertThat(((AssistantThinkingBlock) restored.content().get(0)).signature()).isEqualTo("sig");
        assertThat(restored.content().get(1)).isInstanceOf(AssistantTextBlock.class);
        assertThat(restored.content().get(2)).isInstanceOf(AssistantToolUseBlock.class);
        AssistantToolUseBlock toolUse = (AssistantToolUseBlock) restored.content().get(2);
        assertThat(toolUse.id()).isEqualTo("toolu_1");
        assertThat(toolUse.name()).isEqualTo("file_read");
        assertThat(((Map<?, ?>) toolUse.input()).get("path")).isEqualTo("README.md");
        assertThat(restored.textContent()).isEqualTo("I will inspect.");
    }

    @Test
    void legacyPlainAssistantTextStillLoadsAsTextBlock() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        MessageRow row = new MessageRow(
            id,
            UUID.randomUUID(),
            Role.ASSISTANT,
            "plain answer",
            1,
            now);

        AssistantMessage restored = (AssistantMessage) row.toMessage();

        assertThat(restored.uuid()).isEqualTo(id);
        assertThat(restored.createdAt()).isEqualTo(now);
        assertThat(restored.content()).containsExactly(new AssistantTextBlock("plain answer"));
    }
}
