package com.clawcode.agent.tools;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.clawcode.agent.shared.message.Message;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResult(
    String toolCallId,
    String toolName,
    Object output,
    boolean isError,
    String errorMessage,
    @JsonIgnore
    List<Message> contextMessages
) {

    public ToolResult {
        contextMessages = List.copyOf(contextMessages == null ? List.of() : contextMessages);
    }

    public static ToolResult success(String toolCallId, String toolName, Object output) {
        return success(toolCallId, toolName, output, List.of());
    }

    public static ToolResult success(
        String toolCallId,
        String toolName,
        Object output,
        List<Message> contextMessages
    ) {
        return new ToolResult(toolCallId, toolName, output, false, null, contextMessages);
    }

    public static ToolResult error(String toolCallId, String toolName, String errorMessage) {
        return error(toolCallId, toolName, errorMessage, List.of());
    }

    public static ToolResult error(
        String toolCallId,
        String toolName,
        String errorMessage,
        List<Message> contextMessages
    ) {
        return new ToolResult(toolCallId, toolName, null, true, errorMessage, contextMessages);
    }
}
