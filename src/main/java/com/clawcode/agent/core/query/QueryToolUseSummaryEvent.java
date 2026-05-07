package com.clawcode.agent.core.query;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties({"type"})
public record QueryToolUseSummaryEvent(
    int round,
    @JsonProperty("total_tool_calls")
    int totalToolCalls,
    @JsonProperty("compacted_results")
    int compactedResults,
    @JsonProperty("error_results")
    int errorResults,
    List<String> paths,
    String summary
) implements QueryEvent {

    public QueryToolUseSummaryEvent {
        paths = List.copyOf(paths == null ? List.of() : paths);
        summary = summary == null ? "" : summary;
    }
}
