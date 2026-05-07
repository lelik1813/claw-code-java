package com.clawcode.agent.core.query;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryTranscriptUpdateEventTest {

    @Test
    void carriesTurnTranscriptUpdate() {
        var update = new TurnTranscriptUpdate(List.of());
        var event = new QueryTranscriptUpdateEvent(update);

        assertThat(event.update()).isSameAs(update);
    }

    @Test
    void notRegisteredInJsonSubTypes() {
        var subtypes = QueryEvent.class.getAnnotation(JsonSubTypes.class);

        assertThat(subtypes).isNotNull();
        boolean registered = Arrays.stream(subtypes.value())
            .anyMatch(t -> t.value() == QueryTranscriptUpdateEvent.class);

        assertThat(registered).isFalse();
    }
}
