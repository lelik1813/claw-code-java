package com.clawcode.agent.core.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryResultEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesWithResultTypeDiscriminator() throws Exception {
        var event = new QueryResultEvent(true, "end_turn", 1234L, 5);

        String json = mapper.writeValueAsString(event);

        assertThat(json).contains("\"type\":\"result\"");
        assertThat(json).contains("\"success\":true");
        assertThat(json).contains("\"stop_reason\":\"end_turn\"");
        assertThat(json).contains("\"duration_ms\":1234");
        assertThat(json).contains("\"num_turns\":5");
        assertThat(json).contains("\"permission_denials\":0");
        assertThat(json).doesNotContain("\"stopReason\"");
        assertThat(json).doesNotContain("\"durationMs\"");
        assertThat(json).doesNotContain("\"numTurns\"");
        assertThat(json).doesNotContain("\"permissionDenials\"");
    }

    @Test
    void serializesWithUsage() throws Exception {
        var usage = new QueryResultEvent.Usage(100L, 50L);
        var event = new QueryResultEvent(true, "tool_use", usage, 5678L, 3, 1);

        String json = mapper.writeValueAsString(event);

        assertThat(json).contains("\"type\":\"result\"");
        assertThat(json).contains("\"inputTokens\":100");
        assertThat(json).contains("\"outputTokens\":50");
        assertThat(json).contains("\"permission_denials\":1");
        assertThat(json).doesNotContain("\"permissionDenials\"");
    }

    @Test
    void roundTripDeserializesAsQueryResultEvent() throws Exception {
        var usage = new QueryResultEvent.Usage(200L, 75L);
        var original = new QueryResultEvent(false, "max_tool_rounds", usage, 9999L, 10, 2);

        String json = mapper.writeValueAsString(original);
        var deserialized = (QueryResultEvent) mapper.readValue(json, QueryEvent.class);

        assertThat(deserialized.success()).isFalse();
        assertThat(deserialized.stopReason()).isEqualTo("max_tool_rounds");
        assertThat(deserialized.durationMs()).isEqualTo(9999L);
        assertThat(deserialized.numTurns()).isEqualTo(10);
        assertThat(deserialized.permissionDenials()).isEqualTo(2);
        assertThat(deserialized.usage()).isNotNull();
        assertThat(deserialized.usage().inputTokens()).isEqualTo(200L);
        assertThat(deserialized.usage().outputTokens()).isEqualTo(75L);
    }

    @Test
    void roundTripWithNullUsage() throws Exception {
        var original = new QueryResultEvent(true, "end_turn", null, 500L, 1, 0);

        String json = mapper.writeValueAsString(original);
        var deserialized = (QueryResultEvent) mapper.readValue(json, QueryEvent.class);

        assertThat(deserialized.success()).isTrue();
        assertThat(deserialized.stopReason()).isEqualTo("end_turn");
        assertThat(deserialized.usage()).isNull();
    }

    @Test
    void convenienceConstructorDefaultsUsageToNullAndDenialsToZero() throws Exception {
        var event = new QueryResultEvent(true, "end_turn", 100L, 2);

        assertThat(event.usage()).isNull();
        assertThat(event.permissionDenials()).isZero();
    }

    @Test
    void jsonDoesNotContainMessages() throws Exception {
        var event = new QueryResultEvent(true, "end_turn", 1000L, 1);

        String json = mapper.writeValueAsString(event);

        assertThat(json).doesNotContain("messages");
    }
}
