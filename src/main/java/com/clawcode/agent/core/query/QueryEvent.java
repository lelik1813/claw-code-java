package com.clawcode.agent.core.query;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = QueryStreamStartedEvent.class, name = "started"),
    @JsonSubTypes.Type(value = QueryTextDeltaEvent.class, name = "delta"),
    @JsonSubTypes.Type(value = QueryCompletedEvent.class, name = "completed"),
    @JsonSubTypes.Type(value = QueryErrorEvent.class, name = "error"),
    @JsonSubTypes.Type(value = QueryStopReasonEvent.class, name = "stop_reason"),
    @JsonSubTypes.Type(value = QueryUsageEvent.class, name = "usage"),
    @JsonSubTypes.Type(value = QueryToolCallRequestedEvent.class, name = "tool_call"),
    @JsonSubTypes.Type(value = QueryToolResultEvent.class, name = "tool_result"),
    @JsonSubTypes.Type(value = QueryToolUseSummaryEvent.class, name = "tool_use_summary"),
    @JsonSubTypes.Type(value = QueryResultEvent.class, name = "result")
})
public interface QueryEvent {
}
