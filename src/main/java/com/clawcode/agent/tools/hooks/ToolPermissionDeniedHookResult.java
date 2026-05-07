package com.clawcode.agent.tools.hooks;

import com.clawcode.agent.shared.message.Message;
import java.util.List;

public record ToolPermissionDeniedHookResult(
    String overrideReason,
    List<Message> messages
) {

    public ToolPermissionDeniedHookResult {
        if (overrideReason != null && overrideReason.isBlank()) {
            throw new IllegalArgumentException("overrideReason must not be blank");
        }
        messages = List.copyOf(messages == null ? List.of() : messages);
    }

    public static ToolPermissionDeniedHookResult continueDefault() {
        return new ToolPermissionDeniedHookResult(null, List.of());
    }

    public static ToolPermissionDeniedHookResult continueWith(List<Message> messages) {
        return new ToolPermissionDeniedHookResult(null, messages);
    }

    public static ToolPermissionDeniedHookResult overrideReason(String reason, List<Message> messages) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("overrideReason must not be null or blank");
        }
        return new ToolPermissionDeniedHookResult(reason, messages);
    }
}
