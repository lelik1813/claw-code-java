package com.clawcode.agent.core.query;

import com.clawcode.agent.model.ModelToolDefinition;
import com.clawcode.agent.shared.message.Message;
import java.util.List;

public final class ContextBudgetGuard {

    public static final String FAILURE_MESSAGE =
        "Context is too large for this model request. Run /compact or start a new session, then retry.";

    private final ModelRequestSizeEstimator estimator;

    public ContextBudgetGuard(ModelRequestSizeEstimator estimator) {
        this.estimator = estimator;
    }

    public ContextBudgetCheck check(
        String systemPrompt,
        List<Message> messages,
        List<ModelToolDefinition> tools,
        int maxChars
    ) {
        long estimated = estimator.estimate(systemPrompt, messages, tools);
        int estimatedChars = clampToInt(estimated);
        boolean withinBudget = estimated <= maxChars;
        return new ContextBudgetCheck(
            withinBudget,
            estimatedChars,
            maxChars,
            withinBudget ? null : FAILURE_MESSAGE);
    }

    private int clampToInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
