package com.clawcode.agent.tools.hooks;

import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.tools.ToolResult;
import java.util.List;
import java.util.Objects;

public record ToolPostHookResult(
    ToolResult result,
    List<Message> messages
) {

    public ToolPostHookResult {
        Objects.requireNonNull(result, "result must not be null");
        messages = List.copyOf(messages == null ? List.of() : messages);
    }

    public static ToolPostHookResult continueWith(ToolResult result, List<Message> messages) {
        return new ToolPostHookResult(result, messages);
    }
}
