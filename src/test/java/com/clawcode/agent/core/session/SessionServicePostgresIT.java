package com.clawcode.agent.core.session;

import com.clawcode.agent.model.ModelClient;
import com.clawcode.agent.model.ModelCompletedEvent;
import com.clawcode.agent.model.ModelStreamStartedEvent;
import com.clawcode.agent.model.ModelTextDeltaEvent;
import com.clawcode.agent.persistence.TranscriptStore;
import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.UserMessage;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "PERSISTENCE_BACKEND=r2dbc",
    "app.persistence.backend=r2dbc",
    "spring.autoconfigure.exclude="
})
class SessionServicePostgresIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    SessionService sessionService;

    @Autowired
    TranscriptStore transcriptStore;

    @Test
    void fullPromptLifecyclePersistsToPostgres() {
        String sessionId = sessionService.create()
            .block(Duration.ofSeconds(5)).sessionId();

        sessionService.submitPrompt(sessionId, "What is 2+2?")
            .blockLast(Duration.ofSeconds(5));

        List<Message> transcript = transcriptStore.load(sessionId, null)
            .block(Duration.ofSeconds(5));

        assertThat(transcript).hasSize(2);
        assertThat(transcript.get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) transcript.get(0)).content()).isEqualTo("What is 2+2?");
        assertThat(transcript.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) transcript.get(1)).textContent()).isEqualTo("The answer is 4.");
    }

    @Test
    void sessionSurvivesAcrossLookups() {
        SessionRecord created = sessionService.create()
            .block(Duration.ofSeconds(5));

        SessionRecord loaded = sessionService.get(created.sessionId())
            .block(Duration.ofSeconds(5));

        assertThat(loaded).isNotNull();
        assertThat(loaded.sessionId()).isEqualTo(created.sessionId());
        assertThat(java.time.Duration.between(created.createdAt(), loaded.createdAt()).abs())
            .isLessThan(java.time.Duration.ofSeconds(1));
    }

    @TestConfiguration
    static class StubModelConfig {

        @Bean
        @Primary
        ModelClient stubModelClient() {
            return request -> Flux.just(
                new ModelStreamStartedEvent("stub"),
                new ModelTextDeltaEvent("The answer is 4."),
                new ModelCompletedEvent()
            );
        }
    }
}
