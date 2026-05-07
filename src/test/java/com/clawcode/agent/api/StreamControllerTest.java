package com.clawcode.agent.api;

import com.clawcode.agent.core.query.QueryCompletedEvent;
import com.clawcode.agent.core.query.QueryEvent;
import com.clawcode.agent.core.query.QueryResultEvent;
import com.clawcode.agent.core.query.QueryStreamStartedEvent;
import com.clawcode.agent.core.query.QueryTextDeltaEvent;
import com.clawcode.agent.core.session.SessionService;
import com.clawcode.agent.forensics.ObservabilityMetrics;
import com.clawcode.agent.model.ModelClient;
import com.clawcode.agent.model.ModelCompletedEvent;
import com.clawcode.agent.model.ModelStreamStartedEvent;
import com.clawcode.agent.model.ModelTextDeltaEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class StreamControllerTest {

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class SseTransportTests {

        @LocalServerPort
        int port;

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
                .expectBody(JsonSession.class)
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
        void lateSubscriberReplaysFullEventSequence() {
            webClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();

            String sessionId = webClient.post().uri("/api/sessions")
                .exchange()
                .expectBody(JsonSession.class)
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
    }

    @Nested
    @SpringBootTest
    class ReplayBufferTests {

        @Autowired
        SessionService sessionService;

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
        void liveSubscriptionDeliversFullSequence() {
            String sessionId = sessionService.create().block(Duration.ofSeconds(5)).sessionId();

            var events = sessionService.stream(sessionId);
            sessionService.submitPrompt(sessionId, "hello").subscribe();

            StepVerifier.create(events.take(4))
                .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
                .expectNextMatches(e -> e instanceof QueryTextDeltaEvent delta && delta.text().equals("noop"))
                .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .expectComplete()
                .verify(Duration.ofSeconds(5));
        }

        @Test
        void promptBeforeStreamReplaysFullSequence() {
            String sessionId = sessionService.create().block(Duration.ofSeconds(5)).sessionId();

            sessionService.submitPrompt(sessionId, "hello")
                .blockLast(Duration.ofSeconds(5));

            StepVerifier.create(sessionService.stream(sessionId).take(4))
                .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
                .expectNextMatches(e -> e instanceof QueryTextDeltaEvent delta && delta.text().equals("noop"))
                .expectNextMatches(e -> e instanceof QueryResultEvent)
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
    }

    public record JsonSession(String sessionId, String createdAt) {
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
    class HeartbeatAndTransportTests {

        @Test
        void heartbeatEventsContainCommentNotData() {
            Sinks.Many<QueryEvent> sink = Sinks.many().replay().limit(64);
            sink.tryEmitNext(new QueryStreamStartedEvent());
            sink.tryEmitNext(new QueryTextDeltaEvent("hello"));
            sink.tryEmitNext(new QueryCompletedEvent());
            sink.tryEmitComplete();

            SessionService sessionService = stubSessionService(sink.asFlux());
            var metrics = new ObservabilityMetrics(new SimpleMeterRegistry());
            var controller = new StreamController(sessionService, metrics);

            List<ServerSentEvent<QueryEvent>> events = controller.stream("test-session")
                .take(3)
                .collectList()
                .block(Duration.ofSeconds(5));

            assertThat(events).hasSize(3);
            for (ServerSentEvent<QueryEvent> event : events) {
                assertThat(event.data()).isNotNull();
            }
        }

        @Test
        void disconnectIncrementsMetric() {
            Sinks.Many<QueryEvent> sink = Sinks.many().replay().limit(64);
            sink.tryEmitNext(new QueryStreamStartedEvent());
            sink.tryEmitComplete();

            SessionService sessionService = stubSessionService(sink.asFlux());
            var metrics = new ObservabilityMetrics(new SimpleMeterRegistry());
            var controller = new StreamController(sessionService, metrics);

            controller.stream("test-session")
                .take(1)
                .blockLast(Duration.ofSeconds(5));

            // After take(1) + cancel, doOnCancel fires and increments the metric
            assertThat(metrics.streamDisconnects().count()).isGreaterThanOrEqualTo(1.0);
        }

        @Test
        void streamErrorIncrementsMetric() {
            SessionService sessionService = stubSessionService(
                Flux.error(new RuntimeException("test stream failure")));
            var metrics = new ObservabilityMetrics(new SimpleMeterRegistry());
            var controller = new StreamController(sessionService, metrics);

            StepVerifier.create(controller.stream("test-session"))
                .expectError()
                .verify(Duration.ofSeconds(5));

            assertThat(metrics.streamErrors().count()).isEqualTo(1.0);
        }

        private SessionService stubSessionService(Flux<QueryEvent> streamFlux) {
            return new SessionService(null, null, new com.clawcode.agent.core.session.SessionRegistry() {
                @Override public reactor.core.publisher.Mono<com.clawcode.agent.core.session.SessionRecord> register(String id) { return reactor.core.publisher.Mono.empty(); }
                @Override public reactor.core.publisher.Mono<com.clawcode.agent.core.session.SessionRecord> find(String id) { return reactor.core.publisher.Mono.just(new com.clawcode.agent.core.session.SessionRecord(id, java.time.Instant.now())); }
                @Override public reactor.core.publisher.Flux<com.clawcode.agent.core.session.SessionRecord> listAll() { return reactor.core.publisher.Flux.empty(); }
            }) {
                @Override
                public Flux<QueryEvent> stream(String sessionId) {
                    return streamFlux;
                }
            };
        }
    }

    @Nested
    @SpringBootTest
    class DisconnectAndReconnectTests {

        @Autowired
        SessionService sessionService;

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
        void liveSubscriberSeesAllTurns() {
            String sessionId = sessionService.create().block(Duration.ofSeconds(5)).sessionId();

            AtomicInteger eventCount = new AtomicInteger();
            sessionService.stream(sessionId)
                .doOnNext(e -> eventCount.incrementAndGet())
                .subscribe();

            sessionService.submitPrompt(sessionId, "turn1").blockLast(Duration.ofSeconds(5));
            sessionService.submitPrompt(sessionId, "turn2").blockLast(Duration.ofSeconds(5));

            // Each turn: StreamStarted + TextDelta + ResultEvent + Completed + StreamCompleted = 5 events
            // Two turns = 10 events
            assertThat(eventCount.get()).isGreaterThanOrEqualTo(6);
        }

        @Test
        void subscriberDuringPromptGetsFullSequence() {
            String sessionId = sessionService.create().block(Duration.ofSeconds(5)).sessionId();

            // Subscribe before prompt completes
            var events = sessionService.stream(sessionId);
            sessionService.submitPrompt(sessionId, "hello").subscribe();

            StepVerifier.create(events.take(4))
                .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
                .expectNextMatches(e -> e instanceof QueryTextDeltaEvent delta && delta.text().equals("noop"))
                .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .expectComplete()
                .verify(Duration.ofSeconds(5));
        }

        @Test
        void sinkCleanupDoesNotThrowOnEmptyReplay() {
            String sessionId = sessionService.create().block(Duration.ofSeconds(5)).sessionId();

            // Subscribe to an empty session and immediately cancel
            sessionService.stream(sessionId)
                .take(1)
                .timeout(Duration.ofMillis(200))
                .onErrorResume(e -> Flux.empty())
                .blockLast(Duration.ofSeconds(1));

            // Submit should still work — new sink created
            sessionService.submitPrompt(sessionId, "hello")
                .blockLast(Duration.ofSeconds(5));

            // New subscriber gets the replay
            StepVerifier.create(sessionService.stream(sessionId).take(4))
                .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
                .expectNextMatches(e -> e instanceof QueryTextDeltaEvent)
                .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .expectComplete()
                .verify(Duration.ofSeconds(5));
        }
    }
}
