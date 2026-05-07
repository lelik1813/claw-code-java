package com.clawcode.agent.tools.hooks;

import com.clawcode.agent.shared.message.Message;
import java.util.List;
import java.util.Objects;

public record ToolStopHookResult(
    Decision decision,
    String message,
    String stopReason,
    List<Message> messages
) {

    public enum Decision {
        CONTINUE_DEFAULT,
        RETRY,
        FAIL
    }

    public ToolStopHookResult {
        Objects.requireNonNull(decision, "decision must not be null");
        if (decision == Decision.FAIL) {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message must not be null or blank");
            }
            if (stopReason == null || stopReason.isBlank()) {
                throw new IllegalArgumentException("stopReason must not be null or blank");
            }
        }
        messages = List.copyOf(messages == null ? List.of() : messages);
    }

    public static ToolStopHookResult continueDefault() {
        return new ToolStopHookResult(Decision.CONTINUE_DEFAULT, null, null, List.of());
    }

    public static ToolStopHookResult retry(List<Message> messages) {
        return new ToolStopHookResult(Decision.RETRY, null, null, messages);
    }

    public static ToolStopHookResult fail(String message, String stopReason, List<Message> messages) {
        return new ToolStopHookResult(Decision.FAIL, message, stopReason, messages);
    }
}
