package com.clawcode.agent.core.query;

import com.clawcode.agent.model.ModelToolDefinition;
import com.clawcode.agent.shared.message.AssistantContentBlock;
import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.AssistantTextBlock;
import com.clawcode.agent.shared.message.AssistantToolUseBlock;
import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.ToolResultMessage;
import com.clawcode.agent.shared.message.UserMessage;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRequestSizeEstimatorTest {

    private final ModelRequestSizeEstimator estimator = new ModelRequestSizeEstimator();

    @Test
    void countsSystemPromptAndTextMessages() {
        List<Message> messages = List.of(
            user("hello"),
            assistant(new AssistantTextBlock("world")));

        long estimate = estimator.estimate("system", messages, List.of());

        assertThat(estimate).isEqualTo("system".length() + "hello".length() + "world".length());
    }

    @Test
    void countsAssistantToolUseBlocks() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("path", "README.md");
        input.put("limit", 10);

        List<Message> messages = List.of(
            assistant(new AssistantToolUseBlock("call-1", "file_read", input)));

        long estimate = estimator.estimate(null, messages, null);

        assertThat(estimate).isEqualTo("call-1".length() + "file_read".length() + input.toString().length());
    }

    @Test
    void countsToolResultContent() {
        List<Message> messages = List.of(
            toolResult("call-1", "file_read", "tool output"));

        long estimate = estimator.estimate(null, messages, List.of());

        assertThat(estimate).isEqualTo("tool output".length());
    }

    @Test
    void countsToolNamesDescriptionsAndSchemas() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("path"));

        List<ModelToolDefinition> tools = List.of(
            new ModelToolDefinition("file_read", "Read a file", schema));

        long estimate = estimator.estimate(null, List.of(), tools);

        assertThat(estimate).isEqualTo("file_read".length() + "Read a file".length() + schema.toString().length());
    }

    @Test
    void nullAndEmptyInputsCountAsZero() {
        List<Message> messages = List.of(
            user(null),
            assistant((List<AssistantContentBlock>) null),
            toolResult(null, null, null));
        List<ModelToolDefinition> tools = List.of(
            new ModelToolDefinition(null, null, null));

        long estimate = estimator.estimate(null, messages, tools);

        assertThat(estimate).isZero();
    }

    private static UserMessage user(String content) {
        return new UserMessage(UUID.randomUUID(), Instant.EPOCH, content);
    }

    private static AssistantMessage assistant(AssistantContentBlock... blocks) {
        return assistant(List.of(blocks));
    }

    private static AssistantMessage assistant(List<AssistantContentBlock> blocks) {
        return new AssistantMessage(UUID.randomUUID(), Instant.EPOCH, blocks);
    }

    private static ToolResultMessage toolResult(String callId, String name, String content) {
        return new ToolResultMessage(UUID.randomUUID(), Instant.EPOCH, callId, name, content, false);
    }
}
