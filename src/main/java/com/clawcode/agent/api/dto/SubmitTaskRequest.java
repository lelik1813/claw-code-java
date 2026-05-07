package com.clawcode.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitTaskRequest(
    @NotBlank String sessionId,
    @Size(max = 64) String turnId,
    @Size(max = 64) String taskType,
    @NotBlank @Size(max = 128_000) String input
) {}
