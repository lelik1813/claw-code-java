package com.clawcode.agent.tools.hooks;

import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.tools.ToolExecutionContext;
import com.clawcode.agent.tools.ToolResult;
import com.clawcode.agent.tools.ToolUseRequest;
import java.util.List;
import java.util.Objects;

public record ToolPostHookContext(
    ToolUseRequest request,
    ToolExecutionContext executionContext,
    ToolResult result,
    List<Message> messages
) {

    public ToolPostHookContext {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(executionContext, "executionContext must not be null");
        Objects.requireNonNull(result, "result must not be null");
        messages = List.copyOf(messages == null ? List.of() : messages);
    }
}
