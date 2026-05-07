package com.clawcode.agent.cli.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReplayPage(List<ReplayMessage> messages, int nextCursor, boolean hasMore) {}
