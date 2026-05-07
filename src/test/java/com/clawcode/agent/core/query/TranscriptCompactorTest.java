package com.clawcode.agent.core.query;

import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.AssistantTextBlock;
import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.ToolResultMessage;
import com.clawcode.agent.shared.message.UserMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptCompactorTest {

    private final TranscriptCompactor compactor = new TranscriptCompactor();

    @Test
    void summaryContainsCountsAndExcerpts() {
        List<Message> history = List.of(
            user("first user prompt"),
            assistant("first assistant answer"),
            tool("c1", "file_read", "tool output", false),
            user("last user prompt"),
            assistant("last assistant answer"));

        List<Message> compacted = compactor.compact(history, 1);

        assertThat(compacted).hasSize(2);
        assertThat(compacted.get(0)).isInstanceOf(UserMessage.class);
        String summary = ((UserMessage) compacted.get(0)).content();
        assertThat(summary).contains("[conversation compacted]");
        assertThat(summary).contains("omitted_messages: 4");
        assertThat(summary).contains("omitted_user_messages: 2");
        assertThat(summary).contains("omitted_assistant_messages: 1");
        assertThat(summary).contains("omitted_tool_result_messages: 1");
        assertThat(summary).contains("omitted_tool_error_messages: 0");
        assertThat(summary).contains("first_user: first user prompt");
        assertThat(summary).contains("last_user: last user prompt");
        assertThat(summary).contains("first_assistant: first assistant answer");
        assertThat(summary).contains("last_assistant: first assistant answer");
    }

    @Test
    void preservesRecentTailWithoutChanges() {
        Message old = user("old");
        Message keep1 = assistant("keep assistant");
        Message keep2 = tool("c2", "file_search", "keep tool", false);

        List<Message> compacted = compactor.compact(List.of(old, keep1, keep2), 2);

        assertThat(compacted).hasSize(3);
        assertThat(compacted.get(1)).isSameAs(keep1);
        assertThat(compacted.get(2)).isSameAs(keep2);
    }

    @Test
    void countsToolErrors() {
        List<Message> history = List.of(
            tool("c1", "file_read", "ok", false),
            tool("c2", "powershell", "failed", true),
            user("tail"));

        List<Message> compacted = compactor.compact(history, 1);
        String summary = ((UserMessage) compacted.get(0)).content();

        assertThat(summary).contains("omitted_tool_result_messages: 2");
        assertThat(summary).contains("omitted_tool_error_messages: 1");
    }

    @Test
    void summaryDoesNotIncludeRawHugeMiddleToolOutput() {
        String hugeMiddle = "RAW_HUGE_MIDDLE_TEXT_SHOULD_NOT_APPEAR";
        String rawToolOutput = "head " + hugeMiddle + " tail";
        List<Message> history = List.of(
            user("first"),
            tool("c1", "file_read", rawToolOutput, false),
            assistant("last"));

        List<Message> compacted = compactor.compact(history, 1);
        String summary = ((UserMessage) compacted.get(0)).content();

        assertThat(summary).doesNotContain(hugeMiddle);
        assertThat(summary).doesNotContain(rawToolOutput);
        assertThat(compacted.get(1)).isInstanceOf(AssistantMessage.class);
    }

    private static UserMessage user(String content) {
        return new UserMessage(UUID.randomUUID(), Instant.EPOCH, content);
    }

    private static AssistantMessage assistant(String content) {
        return new AssistantMessage(
            UUID.randomUUID(),
            Instant.EPOCH,
            List.of(new AssistantTextBlock(content)));
    }

    private static ToolResultMessage tool(String callId, String name, String content, boolean isError) {
        return new ToolResultMessage(UUID.randomUUID(), Instant.EPOCH, callId, name, content, isError);
    }
}
