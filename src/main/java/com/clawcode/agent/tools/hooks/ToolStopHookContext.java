package com.clawcode.agent.tools.hooks;

import com.clawcode.agent.shared.message.Message;
import java.util.List;

public record ToolStopHookContext(
    String stopReason,
    List<Message> messages
) {

    public ToolStopHookContext {
        stopReason = stopReason == null ? "" : stopReason;
        messages = List.copyOf(messages == null ? List.of() : messages);
    }
}
