package com.clawcode.agent.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Typed request/response DTOs for the claw-code-java REST API.
 * Commands and transport layer use these instead of raw Map/String.
 */
public final class AgentApiDtos {

    private AgentApiDtos() {}

    // ── message ────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubmitMessageRequest(String content, List<String> skillIds) {
        public SubmitMessageRequest {
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("content must not be blank");
            }
        }
    }

    // ── task ───────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubmitTaskRequest(
        String sessionId,
        String turnId,
        String taskType,
        String input
    ) {
        public SubmitTaskRequest {
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("sessionId must not be blank");
            }
            if (input == null || input.isBlank()) {
                throw new IllegalArgumentException("input must not be blank");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskSubmitResult(String taskId, String status, String acceptedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskStatus(String taskId, String status, String updatedAt, String error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskResult(String taskId, String status, String output, String error) {}

    // ── plugin manifest ────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PluginManifest(String name, String id, String version) {}

    // ── plugin list entry (JSON output) ────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PluginEntry(
        String name,
        String id,
        String source,
        String version,
        boolean enabled,
        String installedAt,
        String pathOrUrl
    ) {}
}
