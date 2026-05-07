package com.clawcode.agent.core.session;

import com.clawcode.agent.model.ModelClient;
import com.clawcode.agent.model.ModelCompletedEvent;
import com.clawcode.agent.model.ModelEvent;
import com.clawcode.agent.model.ModelRequest;
import com.clawcode.agent.model.ModelStreamStartedEvent;
import com.clawcode.agent.model.ModelTextDeltaEvent;
import com.clawcode.agent.persistence.TranscriptStore;
import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.Message;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TextAggregationTest {

    @Autowired
    SessionService sessionService;

    @Autowired
    TranscriptStore transcriptStore;

    @Test
    void multipleDeltasAreConcatenatedInOrder() {
        String sessionId = sessionService.create()
            .block(Duration.ofSeconds(5)).sessionId();

        sessionService.submitPrompt(sessionId, "hello")
            .blockLast(Duration.ofSeconds(5));

        List<Message> transcript =
            transcriptStore.load(sessionId, null).block(Duration.ofSeconds(5));

        assertThat(transcript).hasSize(2);
        assertThat(transcript.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) transcript.get(1)).textContent())
            .isEqualTo("Hello, world! How can I help?");
    }

    @TestConfiguration
    static class MultiDeltaModelConfig {

        @Bean
        @Primary
        ModelClient multiDeltaModelClient() {
            return request -> Flux.just(
                new ModelStreamStartedEvent(request.model()),
                new ModelTextDeltaEvent("Hello, "),
                new ModelTextDeltaEvent("world! "),
                new ModelTextDeltaEvent("How can I help?"),
                new ModelCompletedEvent()
            );
        }
    }
}
