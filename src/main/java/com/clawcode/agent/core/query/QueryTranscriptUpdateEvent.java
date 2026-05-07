package com.clawcode.agent.core.query;

/**
 * Internal query event carrying a {@link TurnTranscriptUpdate} payload with only
 * the new non-user messages produced during a turn. NOT registered in
 * {@link QueryEvent @JsonSubTypes} -- never serialized to SSE and never
 * forwarded to clients. SessionService filters this out of the public stream
 * and uses the payload for persistence.
 */
public record QueryTranscriptUpdateEvent(TurnTranscriptUpdate update) implements QueryEvent {
}
