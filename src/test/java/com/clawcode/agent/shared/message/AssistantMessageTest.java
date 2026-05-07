package com.clawcode.agent.shared.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssistantMessageTest {

    @Test
    void mixedBlocksPreserveOrder() {
        var msg = new AssistantMessage(
            UUID.randomUUID(), Instant.now(),
            List.of(
                new AssistantTextBlock("hello "),
                new AssistantToolUseBlock("call_1", "search", Map.of("q", "test")),
                new AssistantTextBlock(" done")
            )
        );

        assertThat(msg.content()).hasSize(3);
        assertThat(msg.content().get(0)).isInstanceOf(AssistantTextBlock.class);
        assertThat(msg.content().get(1)).isInstanceOf(AssistantToolUseBlock.class);
        assertThat(msg.content().get(2)).isInstanceOf(AssistantTextBlock.class);
    }

    @Test
    void textContentConcatenatesOnlyTextBlocks() {
        var msg = new AssistantMessage(
            UUID.randomUUID(), Instant.now(),
            List.of(
                new AssistantTextBlock("hello "),
                new AssistantToolUseBlock("call_1", "search", Map.of("q", "test")),
                new AssistantTextBlock("done")
            )
        );

        assertThat(msg.textContent()).isEqualTo("hello done");
    }
}
