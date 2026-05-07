package com.clawcode.agent.tools.hooks;

import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.tools.ToolExecutionContext;
import com.clawcode.agent.tools.ToolUseRequest;
import java.util.List;
import java.util.Objects;

public record ToolPreHookContext(
    ToolUseRequest request,
    ToolExecutionContext executionContext,
    List<Message> messages
) {

    public ToolPreHookContext {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(executionContext, "executionContext must not be null");
        messages = List.copyOf(messages == null ? List.of() : messages);
    }
}
