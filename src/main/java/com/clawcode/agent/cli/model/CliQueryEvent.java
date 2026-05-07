package com.clawcode.agent.cli.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type",
    visible = true, defaultImpl = CliQueryEvent.Unknown.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = CliQueryEvent.Started.class, name = "started"),
    @JsonSubTypes.Type(value = CliQueryEvent.Delta.class, name = "delta"),
    @JsonSubTypes.Type(value = CliQueryEvent.Completed.class, name = "completed"),
    @JsonSubTypes.Type(value = CliQueryEvent.Error.class, name = "error"),
    @JsonSubTypes.Type(value = CliQueryEvent.ToolCall.class, name = "tool_call"),
    @JsonSubTypes.Type(value = CliQueryEvent.ToolResult.class, name = "tool_result"),
    @JsonSubTypes.Type(value = CliQueryEvent.ToolUseSummary.class, name = "tool_use_summary"),
    @JsonSubTypes.Type(value = CliQueryEvent.StopReason.class, name = "stop_reason"),
    @JsonSubTypes.Type(value = CliQueryEvent.Usage.class, name = "usage"),
    @JsonSubTypes.Type(value = CliQueryEvent.Result.class, name = "result")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface CliQueryEvent {

    String type();

    record Started() implements CliQueryEvent {
        @Override public String type() { return "started"; }
    }

    record Delta(String text) implements CliQueryEvent {
        @Override public String type() { return "delta"; }
    }

    record Completed() implements CliQueryEvent {
        @Override public String type() { return "completed"; }
    }

    record Error(String message, String code) implements CliQueryEvent {
        @Override public String type() { return "error"; }
    }

    record ToolCall(String toolCallId, String toolName, Object input) implements CliQueryEvent {
        public ToolCall(String toolCallId, String toolName) {
            this(toolCallId, toolName, null);
        }

        @Override public String type() { return "tool_call"; }
    }

    record ToolResult(String toolCallId, String toolName, boolean isError, String summary) implements CliQueryEvent {
        @Override public String type() { return "tool_result"; }
    }

    record ToolUseSummary(
        int round,
        @JsonProperty("total_tool_calls") int totalToolCalls,
        @JsonProperty("compacted_results") int compactedResults,
        @JsonProperty("error_results") int errorResults,
        String summary
    ) implements CliQueryEvent {
        @Override public String type() { return "tool_use_summary"; }
    }

    record StopReason(String reason) implements CliQueryEvent {
        @Override public String type() { return "stop_reason"; }
    }

    record Usage(Long inputTokens, Long outputTokens) implements CliQueryEvent {
        @Override public String type() { return "usage"; }
    }

    record Result(
        boolean success,
        @JsonProperty("stop_reason") String stopReason,
        ResultUsage usage,
        @JsonProperty("duration_ms") long durationMs,
        @JsonProperty("num_turns") int numTurns,
        @JsonProperty("permission_denials") int permissionDenials
    ) implements CliQueryEvent {
        @Override public String type() { return "result"; }
    }

    record ResultUsage(Long inputTokens, Long outputTokens) {}

    record Unknown(String type) implements CliQueryEvent {}
}
