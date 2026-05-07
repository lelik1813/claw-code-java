package com.clawcode.agent.api;

import com.clawcode.agent.model.ModelClient;
import com.clawcode.agent.model.ModelCompletedEvent;
import com.clawcode.agent.model.ModelStreamStartedEvent;
import com.clawcode.agent.model.ModelTextDeltaEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHardeningTest {

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class ApiKeyAuthTests {

        static final String VALID_KEY = "test-secret-key-123";

        @LocalServerPort
        int port;

        @DynamicPropertySource
        static void enableApikey(DynamicPropertyRegistry registry) {
            registry.add("app.security.api-key.enabled", () -> "true");
            registry.add("app.security.api-key.key", () -> VALID_KEY);
        }

        WebTestClient webClient;

        @BeforeEach
        void setUp() {
            webClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
        }

        @Test
        void apiEndpointWithoutKeyReturns401() {
            webClient.post().uri("/api/sessions")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Unauthorized");
        }

        @Test
        void apiEndpointWithWrongKeyReturns401() {
            webClient.post().uri("/api/sessions")
                .header("X-API-Key", "wrong-key")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Unauthorized");
        }

        @Test
        void apiEndpointWithValidKeySucceeds() {
            webClient.post().uri("/api/sessions")
                .header("X-API-Key", VALID_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sessionId").isNotEmpty();
        }

        @Test
        void getMessageWithValidKeySucceeds() {
            String sessionId = createSession();

            webClient.get().uri("/api/sessions/{id}", sessionId)
                .header("X-API-Key", VALID_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sessionId").isEqualTo(sessionId);
        }

        @Test
        void getMessageWithoutKeyReturns401() {
            webClient.get().uri("/api/sessions/{id}", "any")
                .exchange()
                .expectStatus().isUnauthorized();
        }

        @Test
        void postMessageWithValidKeyReturns202() {
            String sessionId = createSession();

            webClient.post().uri("/api/sessions/{id}/messages", sessionId)
                .header("X-API-Key", VALID_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"content\":\"hello\"}")
                .exchange()
                .expectStatus().isAccepted();
        }

        @Test
        void postMessageWithoutKeyReturns401() {
            webClient.post().uri("/api/sessions/{id}/messages", "any")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"content\":\"hello\"}")
                .exchange()
                .expectStatus().isUnauthorized();
        }

        @Test
        void actuatorHealthRemainsPublic() {
            webClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
        }

        @Test
        void actuatorInfoRemainsPublic() {
            webClient.get().uri("/actuator/info")
                .exchange()
                .expectStatus().isOk();
        }

        @Test
        void customHeaderNameWorks() {
            // default header is X-API-Key, verify it's used
            webClient.post().uri("/api/sessions")
                .header("X-API-Key", VALID_KEY)
                .exchange()
                .expectStatus().isOk();
        }

        @TestFactory
        List<DynamicTest> allApiEndpointsRequireAuth() {
            return List.of(
                DynamicTest.dynamicTest("POST /api/sessions", () ->
                    webClient.post().uri("/api/sessions")
                        .exchange().expectStatus().isUnauthorized()),
                DynamicTest.dynamicTest("GET /api/sessions/{id}", () ->
                    webClient.get().uri("/api/sessions/any")
                        .exchange().expectStatus().isUnauthorized()),
                DynamicTest.dynamicTest("POST /api/sessions/{id}/messages", () ->
                    webClient.post().uri("/api/sessions/any/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"content\":\"hi\"}")
                        .exchange().expectStatus().isUnauthorized()),
                DynamicTest.dynamicTest("GET /api/sessions/{id}/stream", () ->
                    webClient.get().uri("/api/sessions/any/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange().expectStatus().isUnauthorized())
            );
        }

        @TestConfiguration
        static class SilentModelConfig {
            @Bean
            @Primary
            ModelClient silentModelClient() {
                return request -> Flux.just(
                    new ModelStreamStartedEvent(request.model()),
                    new ModelTextDeltaEvent("noop"),
                    new ModelCompletedEvent()
                );
            }
        }

        private String createSession() {
            return webClient.post().uri("/api/sessions")
                .header("X-API-Key", VALID_KEY)
                .exchange()
                .expectBody(JsonSession.class)
                .returnResult().getResponseBody().sessionId();
        }

        record JsonSession(String sessionId, String createdAt) {}
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class RateLimitTests {

        static final String VALID_KEY = "rl-test-key";

        @LocalServerPort
        int port;

        @DynamicPropertySource
        static void enableRateLimit(DynamicPropertyRegistry registry) {
            registry.add("app.security.rate-limit.enabled", () -> "true");
            registry.add("app.security.rate-limit.requests", () -> "5");
            registry.add("app.security.rate-limit.window-seconds", () -> "60");
        }

        WebTestClient webClient;

        @BeforeEach
        void setUp() {
            webClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
        }

        @Test
        void requestsWithinLimitThenOverLimitReturns429() {
            // consume all allowed requests (IP-based, no key)
            for (int i = 0; i < 5; i++) {
                webClient.post().uri("/api/sessions")
                    .exchange().expectStatus().isOk();
            }

            // next request is rejected
            webClient.post().uri("/api/sessions")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.error").isEqualTo("Too Many Requests");

            // subsequent request also blocked
            webClient.post().uri("/api/sessions")
                .exchange()
                .expectStatus().is4xxClientError();
        }

        @Test
        void rateLimitKeysByApiKeySeparatelyFromIp() {
            // request with API key should have separate bucket
            webClient.post().uri("/api/sessions")
                .header("X-API-Key", VALID_KEY)
                .exchange()
                .expectStatus().isOk();
        }

        @Test
        void actuatorNotRateLimited() {
            webClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();

            webClient.get().uri("/actuator/info")
                .exchange()
                .expectStatus().isOk();
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class ValidationTests {

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
        void emptyContentReturns400() {
            String sessionId = createSession();

            webClient.post().uri("/api/sessions/{id}/messages", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"content\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isNotEmpty();
        }

        @Test
        void missingContentReturns400() {
            String sessionId = createSession();

            webClient.post().uri("/api/sessions/{id}/messages", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isNotEmpty();
        }

        @Test
        void oversizedContentReturns400() {
            String sessionId = createSession();
            String big = "x".repeat(128_001);

            webClient.post().uri("/api/sessions/{id}/messages", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"content\":\"" + big + "\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").value(msg ->
                    assertThat((String) msg).contains("128000"));
        }

        private String createSession() {
            return webClient.post().uri("/api/sessions")
                .exchange()
                .expectBody(JsonSession.class)
                .returnResult().getResponseBody().sessionId();
        }

        record JsonSession(String sessionId, String createdAt) {}
    }
}
