package com.clawcode.agent.api;

import com.clawcode.agent.core.query.QueryCompletedEvent;
import com.clawcode.agent.core.query.QueryStreamStartedEvent;
import com.clawcode.agent.core.query.QueryTextDeltaEvent;
import com.clawcode.agent.core.session.SessionService;
import com.clawcode.agent.model.ModelClient;
import com.clawcode.agent.model.ModelCompletedEvent;
import com.clawcode.agent.model.ModelStreamStartedEvent;
import com.clawcode.agent.model.ModelTextDeltaEvent;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
    "PERSISTENCE_BACKEND=r2dbc",
    "app.persistence.backend=r2dbc",
    "spring.autoconfigure.exclude="
})
class StreamControllerPostgresIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @LocalServerPort
    int port;

    @Autowired
    SessionService sessionService;

    WebTestClient webClient;

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

    @Test
    void streamReturnsTextEventStreamContentType() {
        webClient = WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(10))
            .build();

        String sessionId = webClient.post().uri("/api/sessions")
            .exchange()
            .expectBody(StreamControllerTest.JsonSession.class)
            .returnResult().getResponseBody().sessionId();

        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"hello\"}")
            .exchange()
            .expectStatus().isAccepted();

        webClient.get()
            .uri("/api/sessions/{id}/stream", sessionId)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult(ServerSentEvent.class)
            .getResponseBody()
            .take(1)
            .blockLast(Duration.ofSeconds(5));
    }

    @Test
    void streamReturns404ForUnknownSession() {
        webClient = WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .build();

        webClient.get()
            .uri("/api/sessions/{id}/stream", "nonexistent")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void liveSubscriptionDeliversFullSequence() {
        String sessionId = sessionService.create().block(Duration.ofSeconds(5)).sessionId();

        var events = sessionService.stream(sessionId);
        sessionService.submitPrompt(sessionId, "hello").subscribe();

        StepVerifier.create(events.take(3))
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent delta && delta.text().equals("noop"))
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .expectComplete()
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void promptBeforeStreamReplaysFullSequence() {
        String sessionId = sessionService.create().block(Duration.ofSeconds(5)).sessionId();

        sessionService.submitPrompt(sessionId, "hello")
            .blockLast(Duration.ofSeconds(5));

        StepVerifier.create(sessionService.stream(sessionId).take(3))
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent delta && delta.text().equals("noop"))
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .expectComplete()
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void streamRejectsUnknownSession() {
        StepVerifier.create(sessionService.stream("nonexistent"))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().contains("Session not found"))
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void lateSubscriberReplaysFullEventSequence() {
        webClient = WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(10))
            .build();

        String sessionId = webClient.post().uri("/api/sessions")
            .exchange()
            .expectBody(StreamControllerTest.JsonSession.class)
            .returnResult().getResponseBody().sessionId();

        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"hello\"}")
            .exchange()
            .expectStatus().isAccepted();

        List<ServerSentEvent> events = webClient.get()
            .uri("/api/sessions/{id}/stream", sessionId)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk()
            .returnResult(ServerSentEvent.class)
            .getResponseBody()
            .take(3)
            .collectList()
            .block(Duration.ofSeconds(10));

        assertThat(events).hasSize(3);
        assertThat(events.get(0).data()).asString().doesNotContain("noop");
        assertThat(events.get(1).data()).asString().contains("noop");
        assertThat(events.get(2).data()).asString().doesNotContain("noop");
    }
}
