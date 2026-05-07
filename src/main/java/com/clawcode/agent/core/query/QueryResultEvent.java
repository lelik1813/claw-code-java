package com.clawcode.agent.core.query;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties({"type"})
public record QueryResultEvent(
    @JsonProperty("success") boolean success,
    @JsonProperty("stop_reason") String stopReason,
    @JsonProperty("usage") Usage usage,
    @JsonProperty("duration_ms") long durationMs,
    @JsonProperty("num_turns") int numTurns,
    @JsonProperty("permission_denials") int permissionDenials
) implements QueryEvent {

    public QueryResultEvent(boolean success, String stopReason, long durationMs, int numTurns) {
        this(success, stopReason, null, durationMs, numTurns, 0);
    }

    public record Usage(
        @JsonProperty("inputTokens") Long inputTokens,
        @JsonProperty("outputTokens") Long outputTokens
    ) {
    }
}
