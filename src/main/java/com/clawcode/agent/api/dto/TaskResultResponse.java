package com.clawcode.agent.api.dto;

public record TaskResultResponse(
    String taskId,
    String status,
    String output,
    String error
) {}
