package com.clawcode.agent.core.query;

public record ContextBudgetCheck(
    boolean withinBudget,
    int estimatedChars,
    int maxChars,
    String failureMessage
) {
}
