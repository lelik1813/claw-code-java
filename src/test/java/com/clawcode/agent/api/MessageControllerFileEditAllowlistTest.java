package com.clawcode.agent.api;

import com.clawcode.agent.model.ModelClient;
import com.clawcode.agent.model.ModelEvent;
import com.clawcode.agent.model.ModelRequest;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.tools.enabled=true",
        "app.tools.mode=ALLOWLIST",
        "app.tools.allowed-tools=file_read,file_edit"
    }
)
class MessageControllerFileEditAllowlistTest {

    @LocalServerPort
    int port;

    @Autowired
    SessionInspector sessionInspector;

    WebTestClient webClient;

    @BeforeEach
    void setUp() {
        webClient = WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .build();
        sessionInspector.reset();
    }

    @Test
    void fileEditIsAdvertisedInToolsAndPrompt() {
        String sessionId = createSession();

        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"hello\"}")
            .exchange()
            .expectStatus().isAccepted();

        boolean captured = awaitCondition(Duration.ofSeconds(3), () ->
            sessionInspector.lastRequest() != null
        );
        assertThat(captured).as("model request should be captured").isTrue();

        ModelRequest request = sessionInspector.lastRequest();

        // Tools: file_edit present, file_write and powershell absent
        assertThat(request.tools().stream().map(t -> t.name()))
            .containsExactlyInAnyOrder("file_read", "file_edit");

        // Prompt: file_edit appears in tools listing and guidance
        String prompt = request.systemPrompt();
        assertThat(prompt).contains("file_edit");
        assertThat(prompt).contains("## Tool Selection Guidance");
        assertThat(prompt).contains("prefer **file_edit**");

        // Capability: no "cannot edit" because file_edit is available
        assertThat(prompt).doesNotContain("You cannot edit");

        // No full-write tools advertised
        assertThat(request.tools().stream().map(t -> t.name()))
            .doesNotContain("file_write", "powershell");
    }

    private boolean awaitCondition(Duration timeout,
                                   java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            try { Thread.sleep(50); } catch (InterruptedException e) { return false; }
        }
        return false;
    }

    private String createSession() {
        return webClient.post().uri("/api/sessions")
            .exchange()
            .expectBody(JsonSession.class)
            .returnResult()
            .getResponseBody()
            .sessionId();
    }

    record JsonSession(String sessionId, String createdAt) {
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        ModelClient inspectableModelClient(SessionInspector inspector) {
            return request -> {
                inspector.capture(request);
                return inspector.responseFn().apply(request);
            };
        }

        @Bean
        SessionInspector sessionInspector() {
            return new SessionInspector();
        }
    }
}
