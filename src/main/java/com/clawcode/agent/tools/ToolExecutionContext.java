package com.clawcode.agent.tools;

public record ToolExecutionContext(
    String turnId,
    String sessionId,
    String model,
    String systemPrompt,
    boolean explicitDestructiveApproval
) {

    public ToolExecutionContext(String turnId, String sessionId, String model, String systemPrompt) {
        this(turnId, sessionId, model, systemPrompt, false);
    }
}
