package com.clawcode.agent.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ReplayResponse(
    @JsonProperty("messages") List<ReplayMessage> messages,
    @JsonProperty("nextCursor") int nextCursor,
    @JsonProperty("hasMore") boolean hasMore
) {
}
