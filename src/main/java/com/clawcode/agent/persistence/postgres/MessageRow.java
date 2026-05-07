package com.clawcode.agent.persistence.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawcode.agent.shared.message.AssistantContentBlock;
import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.AssistantTextBlock;
import com.clawcode.agent.shared.message.AssistantThinkingBlock;
import com.clawcode.agent.shared.message.AssistantToolUseBlock;
import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.ToolResultMessage;
import com.clawcode.agent.shared.message.UserMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("messages")
public record MessageRow(
    @Id UUID id,
    UUID sessionId,
    Role role,
    String content,
    int sequenceNo,
    Instant createdAt
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ASSISTANT_BLOCKS_KEY = "assistantContentBlocks";

    public static MessageRow from(UUID sessionId, Message message, int sequenceNo) {
        return new MessageRow(
            message.uuid(),
            sessionId,
            roleOf(message),
            extractContent(message),
            sequenceNo,
            message.createdAt()
        );
    }

    public Message toMessage() {
        return switch (role) {
            case USER -> new UserMessage(id, createdAt, content);
            case ASSISTANT -> new AssistantMessage(id, createdAt, deserializeAssistantContent(content));
            case TOOL -> {
                try {
                    Map<String, Object> fields = MAPPER.readValue(content, Map.class);
                    yield new ToolResultMessage(
                        id, createdAt,
                        (String) fields.get("toolCallId"),
                        (String) fields.get("toolName"),
                        (String) fields.get("content"),
                        Boolean.TRUE.equals(fields.get("isError"))
                    );
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("Failed to deserialize tool result", e);
                }
            }
        };
    }

    private static Role roleOf(Message message) {
        if (message instanceof UserMessage) return Role.USER;
        if (message instanceof AssistantMessage) return Role.ASSISTANT;
        if (message instanceof ToolResultMessage) return Role.TOOL;
        throw new IllegalArgumentException("Unknown message type: " + message.getClass());
    }

    private static String extractContent(Message message) {
        if (message instanceof UserMessage m) return m.content();
        if (message instanceof AssistantMessage m) return serializeAssistantContent(m);
        if (message instanceof ToolResultMessage m) {
            try {
                return MAPPER.writeValueAsString(Map.of(
                    "toolCallId", m.toolCallId(),
                    "toolName", m.toolName(),
                    "content", m.content() != null ? m.content() : "",
                    "isError", m.isError()
                ));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize tool result", e);
            }
        }
        throw new IllegalArgumentException("Unknown message type: " + message.getClass());
    }

    private static String serializeAssistantContent(AssistantMessage message) {
        List<AssistantContentBlock> blocks = message.content();
        if (blocks.size() == 1 && blocks.getFirst() instanceof AssistantTextBlock text) {
            return text.text();
        }
        try {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put(ASSISTANT_BLOCKS_KEY, blocks.stream()
                .map(MessageRow::toAssistantBlockMap)
                .toList());
            return MAPPER.writeValueAsString(wrapper);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize assistant content blocks", e);
        }
    }

    private static List<AssistantContentBlock> deserializeAssistantContent(String content) {
        if (content == null || content.isBlank()) {
            return List.of(new AssistantTextBlock(""));
        }
        try {
            Map<String, Object> wrapper = MAPPER.readValue(content, Map.class);
            Object rawBlocks = wrapper.get(ASSISTANT_BLOCKS_KEY);
            if (!(rawBlocks instanceof List<?> list)) {
                return List.of(new AssistantTextBlock(content));
            }
            List<AssistantContentBlock> blocks = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> block) {
                    blocks.add(fromAssistantBlockMap(block));
                }
            }
            return blocks.isEmpty() ? List.of(new AssistantTextBlock("")) : List.copyOf(blocks);
        } catch (JsonProcessingException e) {
            return List.of(new AssistantTextBlock(content));
        }
    }

    private static Map<String, Object> toAssistantBlockMap(AssistantContentBlock block) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (block instanceof AssistantTextBlock text) {
            fields.put("type", "text");
            fields.put("text", text.text());
            return fields;
        }
        if (block instanceof AssistantToolUseBlock toolUse) {
            fields.put("type", "tool_use");
            fields.put("id", toolUse.id());
            fields.put("name", toolUse.name());
            fields.put("input", toolUse.input());
            return fields;
        }
        if (block instanceof AssistantThinkingBlock thinking) {
            fields.put("type", "thinking");
            fields.put("thinking", thinking.thinking());
            if (thinking.signature() != null && !thinking.signature().isBlank()) {
                fields.put("signature", thinking.signature());
            }
            return fields;
        }
        throw new IllegalArgumentException("Unknown assistant content block: " + block.getClass());
    }

    private static AssistantContentBlock fromAssistantBlockMap(Map<?, ?> block) {
        String type = stringValue(block.get("type"));
        return switch (type) {
            case "text" -> new AssistantTextBlock(stringValue(block.get("text")));
            case "tool_use" -> new AssistantToolUseBlock(
                stringValue(block.get("id")),
                stringValue(block.get("name")),
                block.get("input") != null ? block.get("input") : Map.of());
            case "thinking" -> new AssistantThinkingBlock(
                stringValue(block.get("thinking")),
                stringValue(block.get("signature")));
            default -> new AssistantTextBlock("");
        };
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
