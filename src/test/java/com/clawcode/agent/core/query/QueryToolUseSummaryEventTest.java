package com.clawcode.agent.core.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryToolUseSummaryEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesWithToolUseSummaryTypeAndFields() throws Exception {
        var event = new QueryToolUseSummaryEvent(
            2,
            5,
            1,
            2,
            List.of("src/A.java", "src/B.java"),
            "[tool batch summary]\ntotal_tool_calls: 5");

        String json = mapper.writeValueAsString(event);

        assertThat(json).contains("\"type\":\"tool_use_summary\"");
        assertThat(json).contains("\"round\":2");
        assertThat(json).contains("\"total_tool_calls\":5");
        assertThat(json).contains("\"compacted_results\":1");
        assertThat(json).contains("\"error_results\":2");
        assertThat(json).contains("\"paths\":[\"src/A.java\",\"src/B.java\"]");
        assertThat(json).contains("\"summary\":\"[tool batch summary]\\ntotal_tool_calls: 5\"");
        assertThat(json).doesNotContain("totalToolCalls");
        assertThat(json).doesNotContain("compactedResults");
        assertThat(json).doesNotContain("errorResults");
    }

    @Test
    void roundTripViaQueryEvent() throws Exception {
        var original = new QueryToolUseSummaryEvent(
            3,
            4,
            1,
            0,
            List.of("src/App.java"),
            "summary text");

        String json = mapper.writeValueAsString(original);
        var deserialized = (QueryToolUseSummaryEvent) mapper.readValue(json, QueryEvent.class);

        assertThat(deserialized.round()).isEqualTo(3);
        assertThat(deserialized.totalToolCalls()).isEqualTo(4);
        assertThat(deserialized.compactedResults()).isEqualTo(1);
        assertThat(deserialized.errorResults()).isZero();
        assertThat(deserialized.paths()).containsExactly("src/App.java");
        assertThat(deserialized.summary()).isEqualTo("summary text");
    }

    @Test
    void normalizesNullPathsAndSummary() {
        var event = new QueryToolUseSummaryEvent(0, 0, 0, 0, null, null);

        assertThat(event.paths()).isEmpty();
        assertThat(event.summary()).isEmpty();
    }
}
