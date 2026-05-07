package com.clawcode.agent.tools.hooks;

import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.tools.ToolUseRequest;
import java.util.List;
import java.util.Objects;

public record ToolPreHookResult(
    Decision decision,
    ToolUseRequest request,
    String denyReason,
    List<Message> messages
) {

    public enum Decision {
        CONTINUE,
        DENY
    }

    public ToolPreHookResult {
        Objects.requireNonNull(decision, "decision must not be null");
        if (decision == Decision.CONTINUE) {
            Objects.requireNonNull(request, "request must not be null");
        }
        if (decision == Decision.DENY && isBlank(denyReason)) {
            throw new IllegalArgumentException("denyReason must not be null or blank");
        }
        messages = List.copyOf(messages == null ? List.of() : messages);
    }

    public static ToolPreHookResult continueWith(ToolUseRequest request, List<Message> messages) {
        return new ToolPreHookResult(Decision.CONTINUE, request, null, messages);
    }

    public static ToolPreHookResult deny(String reason, List<Message> messages) {
        return new ToolPreHookResult(Decision.DENY, null, reason, messages);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
