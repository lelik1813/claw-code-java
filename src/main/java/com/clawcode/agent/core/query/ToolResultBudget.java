package com.clawcode.agent.core.query;

import com.clawcode.agent.tools.ToolResult;
import com.clawcode.agent.tools.ToolUseRequest;
import java.util.Map;

public record ToolResultBudget(int maxChars, int excerptChars) {

    public ToolResultBudget {
        if (maxChars < 1000) {
            throw new IllegalArgumentException("maxChars must be at least 1000");
        }
        if (excerptChars < 200) {
            throw new IllegalArgumentException("excerptChars must be at least 200");
        }
        if (excerptChars > maxChars) {
            throw new IllegalArgumentException("excerptChars must be less than or equal to maxChars");
        }
    }
}

record BudgetedToolResult(
    String toolCallId,
    String toolName,
    String content,
    boolean isError,
    boolean compacted,
    int originalChars,
    int shownChars,
    int omittedChars,
    String pathHint
) {
    BudgetedToolResult {
        toolCallId = nullToEmpty(toolCallId);
        toolName = nullToEmpty(toolName);
        content = nullToEmpty(content);
        pathHint = nullToEmpty(pathHint);
        if (originalChars < 0) {
            throw new IllegalArgumentException("originalChars must not be negative");
        }
        if (shownChars < 0) {
            throw new IllegalArgumentException("shownChars must not be negative");
        }
        if (omittedChars < 0) {
            throw new IllegalArgumentException("omittedChars must not be negative");
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

record ToolCallOutcome(
    ToolUseRequest request,
    ToolResult rawResult,
    BudgetedToolResult budgeted
) {
    ToolCallOutcome {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (rawResult == null) {
            throw new IllegalArgumentException("rawResult must not be null");
        }
        if (budgeted == null) {
            throw new IllegalArgumentException("budgeted must not be null");
        }
    }

    static String pathHintFrom(ToolUseRequest request) {
        if (request == null || !(request.input() instanceof Map<?, ?> map)) {
            return "";
        }
        Object path = map.get("path");
        if (path == null) {
            return "";
        }
        String value = path.toString();
        return value.isBlank() ? "" : value;
    }
}
