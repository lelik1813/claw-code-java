package com.clawcode.agent.api;

import com.clawcode.agent.model.ModelClient;
import com.clawcode.agent.model.ModelCompletedEvent;
import com.clawcode.agent.model.ModelEvent;
import com.clawcode.agent.model.ModelRequest;
import com.clawcode.agent.model.ModelStreamStartedEvent;
import com.clawcode.agent.model.ModelTextDeltaEvent;
import com.clawcode.agent.model.ModelToolUseEvent;
import com.clawcode.agent.shared.message.ToolResultMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
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
        "app.tools.allowed-tools=file_list,file_read,file_write"
    }
)
class MessageControllerReadBeforeWriteTest {

    @LocalServerPort
    int port;

    @Autowired
    SessionInspector sessionInspector;

    WebTestClient webClient;

    private String savedAllowedRoots;
    private Path testRoot;
    private Path testFile;

    @BeforeEach
    void setUp() throws Exception {
        savedAllowedRoots = System.getProperty("app.tools.allowed-roots");
        testRoot = Path.of("target", "api-read-before-write-test").toAbsolutePath().normalize();
        System.setProperty("app.tools.allowed-roots", testRoot.toString());
        Files.createDirectories(testRoot);
        testFile = testRoot.resolve("existing.txt");
        Files.writeString(testFile, "original content");

        webClient = WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .build();
        sessionInspector.reset();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (savedAllowedRoots != null) {
            System.setProperty("app.tools.allowed-roots", savedAllowedRoots);
        } else {
            System.clearProperty("app.tools.allowed-roots");
        }
        if (testRoot != null) {
            try (var stream = Files.walk(testRoot)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
            }
        }
    }

    @Test
    void deniedFileWriteWithoutReadShowsInModelHistory() throws Exception {
        String filePath = testFile.toString();

        sessionInspector.setResponseFn(new Function<ModelRequest, Flux<ModelEvent>>() {
            @Override
            public Flux<ModelEvent> apply(ModelRequest request) {
                int callNum = sessionInspector.allRequests().size();
                if (callNum == 1) {
                    return Flux.just(new ModelToolUseEvent("c-write", "file_write",
                        Map.of("path", filePath, "content", "overwritten content")));
                }
                ToolResultMessage msg = (ToolResultMessage) request.messages().stream()
                    .filter(m -> m instanceof ToolResultMessage)
                    .filter(m -> ((ToolResultMessage) m).toolName().equals("file_write"))
                    .findFirst().orElse(null);
                if (msg == null || !msg.isError()) {
                    return Flux.error(new RuntimeException(
                        "expected denied ToolResultMessage in history"));
                }
                return Flux.just(
                    new ModelStreamStartedEvent("m"),
                    new ModelTextDeltaEvent("file_write denied without read"),
                    new ModelCompletedEvent());
            }
        });

        String sessionId = createSession();

        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"write to existing file\"}")
            .exchange()
            .expectStatus().isAccepted();

        boolean bothRoundsCompleted = awaitCondition(Duration.ofSeconds(5), () ->
            sessionInspector.allRequests().size() >= 2
        );
        assertThat(bothRoundsCompleted)
            .as("both model rounds should complete within timeout")
            .isTrue();

        ModelRequest secondRequest = sessionInspector.allRequests().get(1);
        ToolResultMessage deniedMsg = (ToolResultMessage) secondRequest.messages().stream()
            .filter(m -> m instanceof ToolResultMessage)
            .filter(m -> ((ToolResultMessage) m).toolName().equals("file_write"))
            .findFirst().orElse(null);
        assertThat(deniedMsg).isNotNull();
        assertThat(deniedMsg.toolCallId()).isEqualTo("c-write");
        assertThat(deniedMsg.isError()).isTrue();
        assertThat(deniedMsg.content()).contains("read the existing file")
            .contains("with file_read first");

        assertThat(Files.readString(testFile))
            .as("file must remain unchanged after denied write")
            .isEqualTo("original content");
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
}
