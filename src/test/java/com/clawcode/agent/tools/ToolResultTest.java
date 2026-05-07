package com.clawcode.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.UserMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void backwardCompatibleFactoriesUseEmptyContextMessages() {
        ToolResult success = ToolResult.success("c1", "echo", "ok");
        ToolResult error = ToolResult.error("c2", "echo", "failed");

        assertThat(success.contextMessages()).isEmpty();
        assertThat(success.isError()).isFalse();
        assertThat(success.output()).isEqualTo("ok");
        assertThat(error.contextMessages()).isEmpty();
        assertThat(error.isError()).isTrue();
        assertThat(error.errorMessage()).isEqualTo("failed");
    }

    @Test
    void successFactoryDefensivelyCopiesContextMessages() {
        List<Message> messages = new ArrayList<>();
        Message first = message("first");
        messages.add(first);

        ToolResult result = ToolResult.success("c1", "echo", "ok", messages);

        messages.add(message("later"));

        assertThat(result.contextMessages()).containsExactly(first);
        assertThatThrownBy(() -> result.contextMessages().add(message("blocked")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void errorFactoryDefensivelyCopiesContextMessages() {
        List<Message> messages = new ArrayList<>();
        Message first = message("first");
        messages.add(first);

        ToolResult result = ToolResult.error("c1", "echo", "failed", messages);

        messages.add(message("later"));

        assertThat(result.contextMessages()).containsExactly(first);
        assertThatThrownBy(() -> result.contextMessages().add(message("blocked")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullContextMessagesBecomeEmptyList() {
        ToolResult result = ToolResult.success("c1", "echo", "ok", null);

        assertThat(result.contextMessages()).isEmpty();
    }

    @Test
    void contextMessagesAreExcludedFromJsonSerialization() throws Exception {
        ToolResult result = ToolResult.success("c1", "echo", "ok", List.of(message("context")));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(result));

        assertThat(json.has("contextMessages")).isFalse();
        assertThat(json.get("toolCallId").asText()).isEqualTo("c1");
        assertThat(json.get("toolName").asText()).isEqualTo("echo");
        assertThat(json.get("output").asText()).isEqualTo("ok");
        assertThat(json.get("isError").asBoolean()).isFalse();
    }

    private static Message message(String content) {
        return new UserMessage(UUID.randomUUID(), Instant.now(), content);
    }
}
