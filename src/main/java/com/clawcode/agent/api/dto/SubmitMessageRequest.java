package com.clawcode.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SubmitMessageRequest(
    @NotBlank @Size(max = 128_000) String content,
    @Size(max = 20) List<String> skillIds
) {

    public SubmitMessageRequest {
        if (skillIds != null) {
            for (String id : skillIds) {
                if (id == null || id.isBlank()) {
                    throw new IllegalArgumentException("skillIds must not contain null or blank values");
                }
            }
        }
    }
}
