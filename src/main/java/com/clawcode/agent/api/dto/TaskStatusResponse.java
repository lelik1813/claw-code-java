package com.clawcode.agent.api.dto;

import java.time.Instant;

public record TaskStatusResponse(
    String taskId,
    String status,
    Instant updatedAt,
    String error
) {}
