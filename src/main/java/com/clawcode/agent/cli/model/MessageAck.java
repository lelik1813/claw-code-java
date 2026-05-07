package com.clawcode.agent.cli.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageAck(String sessionId, boolean accepted) {}
