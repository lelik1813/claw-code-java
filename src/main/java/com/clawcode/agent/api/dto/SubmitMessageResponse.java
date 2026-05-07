package com.clawcode.agent.api.dto;

public record SubmitMessageResponse(
    String sessionId,
    boolean accepted
) {
}
