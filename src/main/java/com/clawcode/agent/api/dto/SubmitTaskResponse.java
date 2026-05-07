package com.clawcode.agent.api.dto;

import java.time.Instant;

public record SubmitTaskResponse(
    String taskId,
    String status,
    Instant acceptedAt
) {}
