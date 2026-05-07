package com.clawcode.agent;

import com.clawcode.agent.core.query.*;
import com.clawcode.agent.core.session.SessionService;
import com.clawcode.agent.model.AnthropicModelClient;
import com.clawcode.agent.model.ModelClient;
import com.clawcode.agent.model.ModelCompletedEvent;
import com.clawcode.agent.model.ModelRequest;
import com.clawcode.agent.model.ModelStreamStartedEvent;
import com.clawcode.agent.model.ModelTextDeltaEvent;
import com.clawcode.agent.persistence.TranscriptStore;
import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.UserMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AgentServerApplicationTests {

    @Autowired
    QueryOrchestrator orchestrator;

    @Autowired
    SessionService sessionService;

    @Autowired
    TranscriptStore transcriptStore;

    @Test
    void contextLoads() {
    }

    @Test
    void runTurnEmitsQueryEvents() {
        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
            "test-model",
            "You are a helpful assistant.",
            List.of()
        );

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent)
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();
    }

    @Test
    void submitPromptPersistsBothMessagesToTranscript() {
        String sessionId = sessionService.create()
            .block(Duration.ofSeconds(5)).sessionId();

        sessionService.submitPrompt(sessionId, "what is 2+2?")
            .blockLast(Duration.ofSeconds(5));

        List<com.clawcode.agent.shared.message.Message> transcript =
            transcriptStore.load(sessionId, null).block(Duration.ofSeconds(5));

        assertThat(transcript).hasSize(2);
        assertThat(transcript.get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) transcript.get(0)).content()).isEqualTo("what is 2+2?");
        assertThat(transcript.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) transcript.get(1)).textContent()).isEqualTo("noop");
    }

    @TestConfiguration
    static class NoopModelConfig {

        @Bean("testModelClient")
        @Primary
        ModelClient testModelClient() {
            return request -> Flux.just(
                new ModelStreamStartedEvent(request.model()),
                new ModelTextDeltaEvent("noop"),
                new ModelCompletedEvent()
            );
        }
    }

    @Nested
    @SpringBootTest(properties = "anthropic.auth-token=")
    class WithoutTokenTest {

        @Autowired
        ApplicationContext context;

        @Autowired
        ModelClient modelClient;

        @Test
        void anthropicClientIsNotCreated() {
            assertThat(context.getBeanNamesForType(AnthropicModelClient.class)).isEmpty();
        }

        @Test
        void fallbackClientIsActive() {
            assertThat(modelClient).isNotInstanceOf(AnthropicModelClient.class);
        }
    }

    @Nested
    @SpringBootTest(properties = "anthropic.auth-token=dummy-test-token")
    class WithTokenTest {

        @Autowired
        ApplicationContext context;

        @Test
        void anthropicClientIsCreated() {
            assertThat(context.getBeanNamesForType(AnthropicModelClient.class)).isNotEmpty();
        }
    }
}
