package com.clawcode.agent.tools.hooks;

import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.tools.ToolExecutionContext;
import com.clawcode.agent.tools.ToolUseRequest;
import java.util.List;
import java.util.Objects;

public record ToolPermissionDeniedHookContext(
    ToolUseRequest request,
    ToolExecutionContext executionContext,
    String reason,
    List<Message> messages
) {

    public ToolPermissionDeniedHookContext {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(executionContext, "executionContext must not be null");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be null or blank");
        }
        messages = List.copyOf(messages == null ? List.of() : messages);
    }
}
