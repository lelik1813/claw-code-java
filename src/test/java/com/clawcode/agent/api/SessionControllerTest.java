package com.clawcode.agent.api;

import com.clawcode.agent.model.ModelClient;
import com.clawcode.agent.model.ModelCompletedEvent;
import com.clawcode.agent.model.ModelTextDeltaEvent;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionControllerTest {

    @LocalServerPort
    int port;

    WebTestClient webClient;

    @BeforeEach
    void setUp() {
        webClient = WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Test
    void createSessionReturnsOk() {
        webClient.post().uri("/api/sessions")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.sessionId").isNotEmpty()
            .jsonPath("$.createdAt").isNotEmpty();
    }

    @Test
    void getCreatedSessionReturnsOk() {
        String sessionId = webClient.post().uri("/api/sessions")
            .exchange()
            .expectBody(JsonSession.class)
            .returnResult()
            .getResponseBody()
            .sessionId();

        webClient.get().uri("/api/sessions/{id}", sessionId)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.sessionId").isEqualTo(sessionId)
            .jsonPath("$.createdAt").isNotEmpty();
    }

    @Test
    void getUnknownSessionReturnsNotFound() {
        webClient.get().uri("/api/sessions/{id}", "nonexistent")
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void replayReturnsEmptyForNewSession() {
        String sessionId = createSession();

        webClient.get().uri("/api/sessions/{id}/replay", sessionId)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.messages").isArray()
            .jsonPath("$.messages").isEmpty()
            .jsonPath("$.nextCursor").isEqualTo(0)
            .jsonPath("$.hasMore").isEqualTo(false);
    }

    @Test
    void replayReturnsMessagesAfterPrompt() {
        String sessionId = createSession();

        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"hello\"}")
            .exchange()
            .expectStatus().isAccepted();

        // Wait for async orchestration to complete
        awaitReplayMessages(sessionId, 2);

        webClient.get().uri("/api/sessions/{id}/replay", sessionId)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.messages").isArray()
            .jsonPath("$.messages[0].role").isEqualTo("user")
            .jsonPath("$.messages[0].content").isEqualTo("hello")
            .jsonPath("$.messages[1].role").isEqualTo("assistant")
            .jsonPath("$.messages[1].content").isEqualTo("session controller response")
            .jsonPath("$.hasMore").isEqualTo(false);
    }

    @Test
    void replayPaginatesWithAfterCursor() {
        String sessionId = createSession();

        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"hello\"}")
            .exchange()
            .expectStatus().isAccepted();

        awaitReplayMessages(sessionId, 2);

        // Request only messages after sequence 1 (skip user message)
        webClient.get().uri(uriBuilder -> uriBuilder
                .path("/api/sessions/{id}/replay")
                .queryParam("after", "1")
                .queryParam("limit", "10")
                .build(sessionId))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.messages").isArray()
            .jsonPath("$.messages[0].role").isEqualTo("assistant")
            .jsonPath("$.messages[0].content").isEqualTo("session controller response");
    }

    @Test
    void replayReturns404ForUnknownSession() {
        webClient.get().uri("/api/sessions/{id}/replay", "nonexistent")
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void replayClampsInvalidLimit() {
        String sessionId = createSession();

        webClient.get().uri(uriBuilder -> uriBuilder
                .path("/api/sessions/{id}/replay")
                .queryParam("limit", "-5")
                .build(sessionId))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.messages").isArray();
    }

    private String createSession() {
        return webClient.post().uri("/api/sessions")
            .exchange()
            .expectBody(JsonSession.class)
            .returnResult()
            .getResponseBody()
            .sessionId();
    }

    private void awaitReplayMessages(String sessionId, int expectedCount) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                ReplayPayload payload = webClient.get()
                    .uri("/api/sessions/{id}/replay", sessionId)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(ReplayPayload.class)
                    .returnResult().getResponseBody();
                if (payload != null && payload.messages() != null
                    && payload.messages().stream().filter(m -> m.role() != null).count() >= expectedCount) {
                    return;
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(100); } catch (InterruptedException e) { return; }
        }
        throw new AssertionError("Timed out waiting for replay messages with role fields");
    }

    @TestConfiguration
    static class DeterministicModelConfig {

        @Bean
        @Primary
        ModelClient deterministicModelClient() {
            return request -> Flux.just(
                new ModelTextDeltaEvent("session controller response"),
                new ModelCompletedEvent()
            );
        }
    }

    record JsonSession(String sessionId, String createdAt) {
    }

    record ReplayPayload(List<ReplayPayloadMessage> messages) {
    }

    record ReplayPayloadMessage(String role) {
    }
}
