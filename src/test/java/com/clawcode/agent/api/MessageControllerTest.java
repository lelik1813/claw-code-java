package com.clawcode.agent.api;

import com.clawcode.agent.model.ModelClient;
import com.clawcode.agent.model.ModelCompletedEvent;
import com.clawcode.agent.model.ModelEvent;
import com.clawcode.agent.model.ModelRequest;
import com.clawcode.agent.model.ModelStreamStartedEvent;
import com.clawcode.agent.model.ModelStopReasonEvent;
import com.clawcode.agent.model.ModelTextDeltaEvent;
import com.clawcode.agent.model.ModelToolUseEvent;
import com.clawcode.agent.config.AppProperties;
import com.clawcode.agent.core.prompt.SystemPromptBuilder;
import com.clawcode.agent.core.query.DefaultQueryOrchestrator;
import com.clawcode.agent.core.query.QueryCompletedEvent;
import com.clawcode.agent.core.query.QueryErrorEvent;
import com.clawcode.agent.core.query.QueryEvent;
import com.clawcode.agent.core.query.QueryProperties;
import com.clawcode.agent.core.query.QueryResultEvent;
import com.clawcode.agent.core.query.QueryToolResultEvent;
import com.clawcode.agent.core.query.QueryTranscriptUpdateEvent;
import com.clawcode.agent.core.session.InMemorySessionRegistry;
import com.clawcode.agent.core.session.SessionService;
import com.clawcode.agent.persistence.InMemoryTranscriptStore;
import com.clawcode.agent.skills.SkillContextService;
import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.AssistantTextBlock;
import com.clawcode.agent.shared.message.AssistantToolUseBlock;
import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.ToolResultMessage;
import com.clawcode.agent.shared.message.UserMessage;
import com.clawcode.agent.tools.ConfigurableToolPermissionPolicy;
import com.clawcode.agent.tools.DefaultToolExecutor;
import com.clawcode.agent.tools.SpringToolRegistry;
import com.clawcode.agent.tools.ToolPermissionProperties;
import com.clawcode.agent.tools.hooks.ToolHookPipeline;
import com.clawcode.agent.tools.hooks.TestToolExecutionHook;
import com.clawcode.agent.tools.hooks.ToolExecutionHook;
import com.clawcode.agent.tools.hooks.ToolPostHookContext;
import com.clawcode.agent.tools.hooks.ToolPostHookResult;
import com.clawcode.agent.tools.hooks.ToolPreHookContext;
import com.clawcode.agent.tools.hooks.ToolPreHookResult;
import com.clawcode.agent.tools.shell.PowerShellTool;
import com.clawcode.agent.tools.shell.PowerShellToolProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import reactor.core.publisher.Mono;

import com.clawcode.agent.api.dto.ReplayMessage;
import com.clawcode.agent.api.dto.ReplayResponse;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.tools.enabled=true",
        "app.tools.mode=ALLOWLIST",
        "app.tools.allowed-tools=file_list,file_read,file_glob,file_search"
    }
)
class MessageControllerTest {

    @LocalServerPort
    int port;

    @Autowired
    SessionInspector sessionInspector;

    @Autowired
    SessionService sessionService;

    @Autowired
    ApiHookScenario apiHookScenario;

    WebTestClient webClient;

    @BeforeEach
    void setUp() {
        webClient = WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .build();
        sessionInspector.reset();
        apiHookScenario.reset();
    }

    @Test
    void validPromptIsAccepted() {
        String sessionId = createSession();

        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"hello\"}")
            .exchange()
            .expectStatus().isAccepted()
            .expectBody()
            .jsonPath("$.sessionId").isEqualTo(sessionId)
            .jsonPath("$.accepted").isEqualTo(true);
    }

    @Test
    void validPromptReturnsImmediately() {
        String sessionId = createSession();

        long start = System.nanoTime();
        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"hello\"}")
            .exchange()
            .expectStatus().isAccepted();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("fire-and-forget: 202 must return within 500ms").isLessThan(500);
    }

    @Test
    void failedOrchestrationStillReturns202() throws Exception {
        String sessionId = createSession();
        CompletableFuture<List<QueryEvent>> eventsFuture = sessionService.stream(sessionId)
            .takeUntil(e -> e instanceof QueryErrorEvent)
            .collectList()
            .toFuture();

        sessionInspector.failNext();

        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"hello\"}")
            .exchange()
            .expectStatus().isAccepted()
            .expectBody()
            .jsonPath("$.accepted").isEqualTo(true);

        List<QueryEvent> events = eventsFuture.get(3, TimeUnit.SECONDS);

        assertThat(events).isNotNull();
        assertThat(events.stream()
            .filter(QueryErrorEvent.class::isInstance)
            .map(QueryErrorEvent.class::cast))
            .anySatisfy(error -> {
                assertThat(error.message()).contains("Simulated model failure");
                assertThat(error.source()).isEqualTo("model");
                assertThat(error.code()).isEqualTo("model_error");
            });
    }

    @Test
    void emptyContentIsRejected() {
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
    void unknownSessionIsRejected() {
        webClient.post().uri("/api/sessions/{id}/messages", "nonexistent")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"hello\"}")
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void skillIdsAreForwardedToOrchestrator() {
        String sessionId = createSession();

        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"hello\",\"skillIds\":[\"my-skill\"]}")
            .exchange()
            .expectStatus().isAccepted();

        boolean captured = awaitCondition(Duration.ofSeconds(3), () ->
            sessionInspector.lastRequest() != null
        );
        assertThat(captured).as("model request should be captured").isTrue();

        String systemPrompt = sessionInspector.lastRequest().systemPrompt();
        assertThat(systemPrompt).contains("Active Skills");
        assertThat(systemPrompt).contains("my-skill");
    }

    @Test
    void systemPromptIsNonNullAndContainsRuntimeContract() {
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
        assertThat(request.systemPrompt()).isNotBlank();
        assertThat(request.systemPrompt()).contains("## Environment");
        assertThat(request.systemPrompt()).contains("## Behavioral Rules");
        assertThat(request.systemPrompt()).contains("## Truthful Reporting");
    }

    @Test
    void readOnlyAllowlistHasCapabilityRestrictionsInPromptAndFilteredTools() {
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
        String prompt = request.systemPrompt();

        assertThat(request.tools().stream().map(t -> t.name()))
            .containsExactlyInAnyOrder("file_list", "file_read", "file_glob", "file_search");

        for (var tool : request.tools()) {
            assertThat(tool.description()).as(tool.name() + " description").isNotBlank();
        }

        // file_read description must carry targeted read-only guidance
        String fileReadDesc = toolDesc(request, "file_read");
        assertThat(fileReadDesc)
            .contains("read-only")
            .containsIgnoringCase("targeted")
            .contains("not directories")
            .contains("file_list")
            .contains("file_glob")
            .contains("file_search");

        // file_list description must advertise direct children listing
        assertThat(toolDesc(request, "file_list"))
            .contains("direct children")
            .contains("not recursive")
            .contains("file_read");

        // file_glob description must advertise bounded filename discovery
        assertThat(toolDesc(request, "file_glob"))
            .contains("glob")
            .contains("bounded")
            .contains("before reading multiple files")
            .contains("file_read");

        // file_search description must advertise content search
        assertThat(toolDesc(request, "file_search"))
            .contains("content search")
            .contains("instead of shell")
            .contains("bounded")
            .contains("limit");

        assertThat(prompt).contains("## Capability Restrictions");
        assertThat(prompt).contains("You cannot edit, create, or delete files");
        assertThat(prompt).contains("You cannot run commands, builds, tests, or git operations");

        assertThat(prompt).contains("file_list", "file_read", "file_glob", "file_search");
        assertThat(prompt).doesNotContain("file_write");
        assertThat(prompt).doesNotContain("file_edit");
        assertThat(prompt).doesNotContain("powershell");

        assertThat(request.tools().stream().map(t -> t.name()))
            .doesNotContain("file_edit");
    }

    @Test
    void deniedFileWriteInSessionWithAllowlistReturnsToolErrorAndExplains() {
        sessionInspector.setResponseFn(request -> {
            int callNum = sessionInspector.allRequests().size();
            if (callNum == 1) {
                return Flux.just(new ModelToolUseEvent("c-deny", "file_write",
                    Map.of("path", "target/test.txt", "content", "test")));
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
                new ModelTextDeltaEvent("I cannot write files. Read-only environment."),
                new ModelCompletedEvent());
        });

        String sessionId = createSession();

        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"write a file\"}")
            .exchange()
            .expectStatus().isAccepted();

        boolean bothRoundsCompleted = awaitCondition(Duration.ofSeconds(5), () ->
            sessionInspector.allRequests().size() >= 2
        );
        assertThat(bothRoundsCompleted)
            .as("both model rounds should complete within timeout")
            .isTrue();

        // First request must not advertise write tools
        ModelRequest firstRequest = sessionInspector.allRequests().get(0);
        assertThat(firstRequest.tools().stream().map(t -> t.name()))
            .doesNotContain("file_write");

        // Second request history must contain denied ToolResultMessage
        ModelRequest secondRequest = sessionInspector.allRequests().get(1);
        ToolResultMessage deniedMsg = (ToolResultMessage) secondRequest.messages().stream()
            .filter(m -> m instanceof ToolResultMessage)
            .filter(m -> ((ToolResultMessage) m).toolName().equals("file_write"))
            .findFirst().orElse(null);
        assertThat(deniedMsg).isNotNull();
        assertThat(deniedMsg.isError()).isTrue();
        assertThat(deniedMsg.content()).contains("is denied");
        assertThat(deniedMsg.content()).contains("Do not retry");
        assertThat(deniedMsg.content()).contains("use an advertised tool");
        assertThat(deniedMsg.content()).doesNotContain("is denied: Tool '");
    }

    @Test
    void deniedFileEditInSessionWithAllowlistReturnsToolErrorAndExplains() {
        sessionInspector.setResponseFn(request -> {
            int callNum = sessionInspector.allRequests().size();
            if (callNum == 1) {
                return Flux.just(new ModelToolUseEvent("c-deny-edit", "file_edit",
                    Map.of("path", "target/test.txt", "old_text", "original", "new_text", "edited")));
            }
            ToolResultMessage msg = (ToolResultMessage) request.messages().stream()
                .filter(m -> m instanceof ToolResultMessage)
                .filter(m -> ((ToolResultMessage) m).toolName().equals("file_edit"))
                .findFirst().orElse(null);
            if (msg == null || !msg.isError()) {
                return Flux.error(new RuntimeException(
                    "expected denied ToolResultMessage in history"));
            }
            return Flux.just(
                new ModelStreamStartedEvent("m"),
                new ModelTextDeltaEvent("I cannot edit files with file_edit in this environment."),
                new ModelCompletedEvent());
        });

        String sessionId = createSession();

        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"edit a file\"}")
            .exchange()
            .expectStatus().isAccepted();

        boolean bothRoundsCompleted = awaitCondition(Duration.ofSeconds(5), () ->
            sessionInspector.allRequests().size() >= 2
        );
        assertThat(bothRoundsCompleted)
            .as("both model rounds should complete within timeout")
            .isTrue();

        // First request must not advertise file_edit
        ModelRequest firstRequest = sessionInspector.allRequests().get(0);
        assertThat(firstRequest.tools().stream().map(t -> t.name()))
            .doesNotContain("file_edit");

        // Second request history must contain denied ToolResultMessage
        ModelRequest secondRequest = sessionInspector.allRequests().get(1);
        ToolResultMessage deniedMsg = (ToolResultMessage) secondRequest.messages().stream()
            .filter(m -> m instanceof ToolResultMessage)
            .filter(m -> ((ToolResultMessage) m).toolName().equals("file_edit"))
            .findFirst().orElse(null);
        assertThat(deniedMsg).isNotNull();
        assertThat(deniedMsg.isError()).isTrue();
        assertThat(deniedMsg.content()).contains("is denied");
        assertThat(deniedMsg.content()).contains("Do not retry");
        assertThat(deniedMsg.content()).contains("use an advertised tool");
    }

    @Test
    void toolLoopReachesMaxRoundsAndCompletesCleanlyViaApi() {
        sessionInspector.setResponseFn(request ->
            Flux.just(new ModelToolUseEvent("c-max", "file_write", Map.of()))
        );

        String sessionId = createSession();

        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"test\"}")
            .exchange()
            .expectStatus().isAccepted();

        boolean reachedMax = awaitCondition(Duration.ofSeconds(10), () ->
            sessionInspector.allRequests().size() >= 10
        );
        assertThat(reachedMax)
            .as("orchestrator should complete all 10 tool rounds")
            .isTrue();

        boolean hasMaxRoundText = awaitCondition(Duration.ofSeconds(5), () -> {
            var response = webClient.get()
                .uri("/api/sessions/{id}/replay?after=0&limit=100", sessionId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ReplayResponse.class)
                .returnResult()
                .getResponseBody();
            if (response == null) return false;
            return response.messages().stream()
                .filter(m -> "assistant".equals(m.role()))
                .anyMatch(m -> m.content() != null
                    && m.content().contains("maximum tool rounds"));
        });
        assertThat(hasMaxRoundText)
            .as("replay should contain the max-round explanation")
            .isTrue();
    }

    @Test
    void nextTurnReceivesFullHistory() {
        sessionInspector.setResponseFn(request -> {
            int callNum = sessionInspector.allRequests().size();
            if (callNum == 1) {
                return Flux.just(
                    new ModelTextDeltaEvent("Let me check the project structure"),
                    new ModelToolUseEvent("c1", "file_list", Map.of("path", ".")),
                    new ModelCompletedEvent()
                );
            }
            if (callNum == 2) {
                return Flux.just(
                    new ModelTextDeltaEvent("Project structure listed."),
                    new ModelCompletedEvent()
                );
            }
            return Flux.just(
                new ModelTextDeltaEvent("ok"),
                new ModelCompletedEvent()
            );
        });

        String sessionId = createSession();

        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"first request\"}")
            .exchange().expectStatus().isAccepted();

        boolean turn1Done = awaitCondition(Duration.ofSeconds(10),
            () -> sessionInspector.allRequests().size() >= 2);
        assertThat(turn1Done).as("turn 1 both model calls completed").isTrue();

        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"second request\"}")
            .exchange().expectStatus().isAccepted();

        boolean turn2Done = awaitCondition(Duration.ofSeconds(10),
            () -> sessionInspector.allRequests().size() >= 3);
        assertThat(turn2Done).as("turn 2 model call completed").isTrue();

        ModelRequest req = sessionInspector.allRequests().get(2);
        List<Message> messages = req.messages();
        assertThat(messages).hasSize(5);

        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) messages.get(0)).content()).isEqualTo("first request");

        assertThat(messages.get(1)).isInstanceOf(AssistantMessage.class);
        AssistantMessage assistant1 = (AssistantMessage) messages.get(1);
        assertThat(assistant1.textContent()).isEqualTo("Let me check the project structure");
        assertThat(assistant1.content()).hasSize(2);
        assertThat(assistant1.content().get(0)).isInstanceOf(AssistantTextBlock.class);
        assertThat(((AssistantTextBlock) assistant1.content().get(0)).text())
            .isEqualTo("Let me check the project structure");
        assertThat(assistant1.content().get(1)).isInstanceOf(AssistantToolUseBlock.class);
        AssistantToolUseBlock toolUse = (AssistantToolUseBlock) assistant1.content().get(1);
        assertThat(toolUse.name()).isEqualTo("file_list");
        assertThat(toolUse.id()).isEqualTo("c1");

        assertThat(messages.get(2)).isInstanceOf(ToolResultMessage.class);
        ToolResultMessage toolResult = (ToolResultMessage) messages.get(2);
        assertThat(toolResult.toolName()).isEqualTo("file_list");
        assertThat(toolResult.toolCallId()).isEqualTo("c1");

        assertThat(messages.get(3)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) messages.get(3)).textContent())
            .isEqualTo("Project structure listed.");

        assertThat(messages.get(4)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) messages.get(4)).content()).isEqualTo("second request");
    }

    @Test
    void largeFileReadResultIsBudgetedBeforeFollowUpModelRequest() throws Exception {
        String rawMiddle = "RAW-MIDDLE-SHOULD-NOT-LEAK";
        String largeOutput = "HEAD-" + "a".repeat(7000) + rawMiddle + "b".repeat(7000) + "-TAIL";
        Path tempRoot = Files.createTempDirectory("message-controller-large-read-");
        String previousAllowedRoots = System.getProperty("app.tools.allowed-roots");
        try {
            Path largeFile = tempRoot.resolve("src").resolve("Large.java");
            Files.createDirectories(largeFile.getParent());
            Files.writeString(largeFile, largeOutput);
            System.setProperty("app.tools.allowed-roots", tempRoot.toString());

            sessionInspector.setResponseFn(request -> {
                int callNum = sessionInspector.allRequests().size();
                if (callNum == 1) {
                    return Flux.just(
                        new ModelToolUseEvent("read-large", "file_read",
                            Map.of("path", "src/Large.java"))
                    );
                }
                return Flux.just(
                    new ModelTextDeltaEvent("large file inspected"),
                    new ModelCompletedEvent()
                );
            });

            String sessionId = createSession();

            webClient.post().uri("/api/sessions/{id}/messages", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"content\":\"read large file\"}")
                .exchange()
                .expectStatus().isAccepted();

            boolean secondModelCall = awaitCondition(Duration.ofSeconds(10),
                () -> sessionInspector.allRequests().size() >= 2);
            assertThat(secondModelCall)
                .as("second model call should receive budgeted tool history")
                .isTrue();

            ModelRequest secondRequest = sessionInspector.allRequests().get(1);
            List<Message> messages = secondRequest.messages();
            ToolResultMessage toolResult = messages.stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .filter(m -> m.toolCallId().equals("read-large"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected read-large ToolResultMessage"));

            assertThat(toolResult.toolName()).isEqualTo("file_read");
            assertThat(toolResult.content())
                .contains("[tool result compacted]")
                .contains("tool_call_id: read-large")
                .contains("path: src/Large.java")
                .contains("original_chars:")
                .doesNotContain(rawMiddle)
                .doesNotContain(largeOutput);

            int toolResultIndex = messages.indexOf(toolResult);
            assertThat(toolResultIndex).isGreaterThanOrEqualTo(0);
            assertThat(messages.get(toolResultIndex + 1)).isInstanceOf(UserMessage.class);
            UserMessage summary = (UserMessage) messages.get(toolResultIndex + 1);
            assertThat(summary.content())
                .contains("[tool batch summary]")
                .contains("total_tool_calls: 1")
                .contains("compacted_results: 1")
                .contains("paths: src/Large.java")
                .doesNotContain(rawMiddle)
                .doesNotContain(largeOutput);

            boolean finalReplayVisible = awaitCondition(Duration.ofSeconds(5), () -> {
                ReplayResponse response = webClient.get()
                    .uri("/api/sessions/{id}/replay?after=0&limit=100", sessionId)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(ReplayResponse.class)
                    .returnResult()
                    .getResponseBody();
                return response != null && response.messages().stream()
                    .filter(m -> "assistant".equals(m.role()))
                    .anyMatch(m -> "large file inspected".equals(m.content()));
            });
            assertThat(finalReplayVisible)
                .as("API/session path should complete through final assistant replay")
                .isTrue();
        } finally {
            if (previousAllowedRoots == null) {
                System.clearProperty("app.tools.allowed-roots");
            } else {
                System.setProperty("app.tools.allowed-roots", previousAllowedRoots);
            }
            deleteRecursively(tempRoot);
        }
    }

    @Test
    void apiPathPreservesHookLifecycleSemanticsWithoutPublicHookEvents() {
        apiHookScenario.denyFileWriteAndAttachAfterFileList();
        sessionInspector.setResponseFn(request -> {
            int callNum = sessionInspector.allRequests().size();
            if (callNum == 1) {
                return Flux.just(
                    new ModelStreamStartedEvent("m"),
                    new ModelToolUseEvent("hook-denied-write", "file_write",
                        Map.of("path", "target/hook-api.txt", "content", "blocked")),
                    new ModelToolUseEvent("hook-list", "file_list", Map.of("path", ".")),
                    new ModelCompletedEvent()
                );
            }
            return Flux.just(
                new ModelStreamStartedEvent("m"),
                new ModelTextDeltaEvent("Hook lifecycle handled through API."),
                new ModelCompletedEvent()
            );
        });

        String sessionId = createSession();

        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"exercise hooks\"}")
            .exchange()
            .expectStatus().isAccepted();

        boolean secondModelCall = awaitCondition(Duration.ofSeconds(10),
            () -> sessionInspector.allRequests().size() >= 2);
        assertThat(secondModelCall).as("hook tool loop should call the model again").isTrue();

        ModelRequest secondRequest = sessionInspector.allRequests().get(1);
        ToolResultMessage denied = secondRequest.messages().stream()
            .filter(ToolResultMessage.class::isInstance)
            .map(ToolResultMessage.class::cast)
            .filter(m -> m.toolCallId().equals("hook-denied-write"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected hook-denied ToolResultMessage"));
        assertThat(denied.toolName()).isEqualTo("file_write");
        assertThat(denied.isError()).isTrue();
        assertThat(denied.content())
            .contains("Tool 'file_write' is denied by hook: api hook denied write")
            .contains("Do not retry");

        assertThat(secondRequest.messages().stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .map(UserMessage::content))
            .contains("hook context from api post");

        boolean finalReplayVisible = awaitCondition(Duration.ofSeconds(10), () -> {
            ReplayResponse response = webClient.get()
                .uri("/api/sessions/{id}/replay?after=0&limit=100", sessionId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ReplayResponse.class)
                .returnResult()
                .getResponseBody();
            return response != null && response.messages().stream()
                .filter(m -> "assistant".equals(m.role()))
                .anyMatch(m -> "Hook lifecycle handled through API.".equals(m.content()));
        });
        assertThat(finalReplayVisible).as("API turn should complete and persist final assistant").isTrue();

        List<QueryEvent> publicEvents = sessionService.stream(sessionId)
            .takeUntil(e -> e instanceof QueryCompletedEvent)
            .collectList()
            .block(Duration.ofSeconds(3));
        assertThat(publicEvents).isNotNull();
        assertThat(publicEvents)
            .noneMatch(QueryTranscriptUpdateEvent.class::isInstance)
            .noneMatch(e -> e.getClass().getSimpleName().contains("Hook"));
    }

    @Test
    void oversizedContextCleanFailureViaApiDoesNotCallModelAgain() {
        String hugeAssistantText = "A".repeat(260000);
        sessionInspector.setResponseFn(request -> Flux.just(
            new ModelTextDeltaEvent(hugeAssistantText),
            new ModelCompletedEvent()));
        String sessionId = createSession();

        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"seed\"}")
            .exchange()
            .expectStatus().isAccepted();

        boolean firstTurnPersisted = awaitCondition(Duration.ofSeconds(10), () -> {
            ReplayResponse response = webClient.get()
                .uri("/api/sessions/{id}/replay?after=0&limit=20", sessionId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ReplayResponse.class)
                .returnResult()
                .getResponseBody();
            return response != null && response.messages().stream()
                .filter(m -> "assistant".equals(m.role()))
                .anyMatch(m -> hugeAssistantText.equals(m.content()));
        });
        assertThat(firstTurnPersisted).as("first turn should persist oversized assistant text").isTrue();
        assertThat(sessionInspector.allRequests()).hasSize(1);

        sessionInspector.setResponseFn(request ->
            Flux.error(new AssertionError("Context failure should not call provider")));
        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"trigger context check\"}")
            .exchange()
            .expectStatus().isAccepted();

        boolean failurePersisted = awaitCondition(Duration.ofSeconds(10), () -> {
            ReplayResponse response = webClient.get()
                .uri("/api/sessions/{id}/replay?after=0&limit=50", sessionId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ReplayResponse.class)
                .returnResult()
                .getResponseBody();
            return response != null && response.messages().stream()
                .filter(m -> "assistant".equals(m.role()))
                .anyMatch(m -> m.content() != null
                    && m.content().contains("Context is too large for this model request"));
        });
        assertThat(failurePersisted).as("context failure should be persisted for replay").isTrue();
        assertThat(sessionInspector.allRequests()).hasSize(1);

        List<Object> terminalEvents = sessionService.stream(sessionId)
            .skipUntil(e -> e instanceof QueryResultEvent r
                && "context_too_large".equals(r.stopReason()))
            .filter(e -> e instanceof QueryResultEvent || e instanceof QueryCompletedEvent)
            .take(2)
            .cast(Object.class)
            .collectList()
            .block(Duration.ofSeconds(3));
        assertThat(terminalEvents).hasSize(2);
        assertThat(terminalEvents.get(0)).isInstanceOf(QueryResultEvent.class);
        assertThat(((QueryResultEvent) terminalEvents.get(0)).success()).isFalse();
        assertThat(terminalEvents.get(1)).isInstanceOf(QueryCompletedEvent.class);
    }

    @Test
    void maxOutputRecoveryViaApiUsesResumeMetaAndReplaysCombinedAnswerOnce() {
        AtomicInteger modelCalls = new AtomicInteger();
        sessionInspector.setResponseFn(request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(
                    new ModelTextDeltaEvent("partial "),
                    new ModelStopReasonEvent("max_tokens"),
                    new ModelCompletedEvent());
            }
            return Flux.just(
                new ModelTextDeltaEvent("continuation"),
                new ModelCompletedEvent());
        });
        String sessionId = createSession();

        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"recover output\"}")
            .exchange()
            .expectStatus().isAccepted();

        boolean recovered = awaitCondition(Duration.ofSeconds(10), () ->
            sessionInspector.allRequests().size() >= 2);
        assertThat(recovered).as("max-output recovery should issue a second provider request").isTrue();

        ModelRequest secondRequest = sessionInspector.allRequests().get(1);
        assertThat(secondRequest.messages().toString())
            .contains("[continuation request]")
            .contains("Resume directly from the exact point where you stopped");

        boolean replayCombined = awaitCondition(Duration.ofSeconds(10), () -> {
            ReplayResponse response = webClient.get()
                .uri("/api/sessions/{id}/replay?after=0&limit=50", sessionId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ReplayResponse.class)
                .returnResult()
                .getResponseBody();
            if (response == null) return false;
            long combinedCount = response.messages().stream()
                .filter(m -> "assistant".equals(m.role()))
                .filter(m -> "partial continuation".equals(m.content()))
                .count();
            boolean metaLeaked = response.messages().stream()
                .anyMatch(m -> m.content() != null && m.content().contains("[continuation request]"));
            return combinedCount == 1 && !metaLeaked;
        });
        assertThat(replayCombined)
            .as("replay should contain combined final assistant text exactly once and no recovery meta")
            .isTrue();
    }

    @Test
    void replayContainsFullTurnTranscriptWithoutDuplicates() {
        sessionInspector.setResponseFn(request -> {
            int callNum = sessionInspector.allRequests().size();
            if (callNum == 1) {
                return Flux.just(
                    new ModelTextDeltaEvent("Let me check the project structure"),
                    new ModelToolUseEvent("c1", "file_list", Map.of("path", ".")),
                    new ModelCompletedEvent()
                );
            }
            return Flux.just(
                new ModelTextDeltaEvent("Project structure listed."),
                new ModelCompletedEvent()
            );
        });

        String sessionId = createSession();

        webClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"first request\"}")
            .exchange().expectStatus().isAccepted();

        boolean turnDone = awaitCondition(Duration.ofSeconds(10),
            () -> sessionInspector.allRequests().size() >= 2);
        assertThat(turnDone).as("turn with tool use should complete").isTrue();

        ReplayResponse response = webClient.get()
            .uri("/api/sessions/{id}/replay?after=0&limit=100", sessionId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(ReplayResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(response).isNotNull();
        List<ReplayMessage> messages = response.messages();
        assertThat(messages).hasSize(4);

        assertThat(messages.get(0).role()).isEqualTo("user");
        assertThat(messages.get(0).content()).isEqualTo("first request");

        assertThat(messages.get(1).role()).isEqualTo("assistant");
        assertThat(messages.get(1).content()).isEqualTo("Let me check the project structure");

        assertThat(messages.get(2).role()).isEqualTo("tool");
        assertThat(messages.get(2).metadata())
            .containsEntry("toolName", "file_list")
            .containsEntry("toolCallId", "c1")
            .containsEntry("isError", false);

        assertThat(messages.get(3).role()).isEqualTo("assistant");
        assertThat(messages.get(3).content()).isEqualTo("Project structure listed.");

        long finalAssistantCount = messages.stream()
            .filter(m -> "assistant".equals(m.role()))
            .filter(m -> "Project structure listed.".equals(m.content()))
            .count();
        assertThat(finalAssistantCount).as("no duplicate final assistant text").isEqualTo(1);
    }

    @Test
    void unsafePowershellDenialIsModelVisibleThroughApiPath() {
        SessionInspector shellInspector = new SessionInspector();
        shellInspector.setResponseFn(request -> {
            int callNum = shellInspector.allRequests().size();
            if (callNum == 1) {
                return Flux.just(
                    new ModelToolUseEvent("shell-deny", "powershell", "git reset --hard"),
                    new ModelCompletedEvent()
                );
            }
            ToolResultMessage msg = request.messages().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .filter(m -> m.toolCallId().equals("shell-deny"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected shell denial ToolResultMessage"));
            if (!msg.isError()
                || !msg.content().contains("Destructive git command denied")
                || msg.content().contains("SecurityException")
                || msg.content().contains("java.lang")
                || msg.content().contains("at com.clawcode")) {
                return Flux.error(new RuntimeException("unsafe shell denial was not model-visible and safe"));
            }
            return Flux.just(
                new ModelTextDeltaEvent("I cannot run that destructive git command."),
                new ModelCompletedEvent()
            );
        });

        var audit = noopAudit();
        var metrics = new com.clawcode.agent.forensics.ObservabilityMetrics(
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        ModelClient modelClient = request -> {
            shellInspector.capture(request);
            return shellInspector.responseFn().apply(request);
        };
        var permissionProperties = new ToolPermissionProperties(
            true, ToolPermissionProperties.Mode.ALLOWLIST, java.util.Set.of("powershell"));
        var registry = new SpringToolRegistry(List.of(
            new PowerShellTool(new PowerShellToolProperties(30))));
        var executor = new DefaultToolExecutor(
            registry,
            new ConfigurableToolPermissionPolicy(permissionProperties),
            audit,
            metrics,
            List.of());
        var transcriptStore = new InMemoryTranscriptStore(audit);
        var orchestrator = new DefaultQueryOrchestrator(
            modelClient,
            executor,
            registry,
            transcriptStore,
            audit,
            metrics,
            new SkillContextService(null),
            permissionProperties,
            new SystemPromptBuilder(),
            new QueryProperties(10, 240000, true, 12, 2),
            new AppProperties(4, 12000, 4000, 4),
            new ToolHookPipeline(List.of()));
        var isolatedSessionService = new SessionService(
            orchestrator, transcriptStore, new InMemorySessionRegistry());
        var isolatedWebClient = WebTestClient.bindToController(new MessageController(isolatedSessionService)).build();
        String sessionId = isolatedSessionService.create().block(Duration.ofSeconds(3)).sessionId();

        isolatedWebClient.post().uri("/api/sessions/{id}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"content\":\"reset git hard\"}")
            .exchange()
            .expectStatus().isAccepted();

        boolean secondModelCall = awaitCondition(Duration.ofSeconds(10),
            () -> shellInspector.allRequests().size() >= 2);
        assertThat(secondModelCall).as("shell denial should be sent back to model").isTrue();

        ModelRequest firstRequest = shellInspector.allRequests().get(0);
        assertThat(firstRequest.tools().stream().map(t -> t.name()))
            .contains("powershell");

        ModelRequest secondRequest = shellInspector.allRequests().get(1);
        ToolResultMessage denied = secondRequest.messages().stream()
            .filter(ToolResultMessage.class::isInstance)
            .map(ToolResultMessage.class::cast)
            .filter(m -> m.toolCallId().equals("shell-deny"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected shell-deny ToolResultMessage"));
        assertThat(denied.toolName()).isEqualTo("powershell");
        assertThat(denied.isError()).isTrue();
        assertThat(denied.content())
            .contains("Destructive git command denied")
            .contains("Explicit user request is required")
            .contains("Do not retry")
            .doesNotContain("SecurityException")
            .doesNotContain("java.lang")
            .doesNotContain("at com.clawcode");

        List<QueryEvent> publicEvents = isolatedSessionService.stream(sessionId)
            .takeUntil(e -> e instanceof QueryCompletedEvent)
            .collectList()
            .block(Duration.ofSeconds(3));
        assertThat(publicEvents).isNotNull();
        assertThat(publicEvents)
            .noneMatch(QueryTranscriptUpdateEvent.class::isInstance)
            .noneMatch(QueryErrorEvent.class::isInstance);
        assertThat(publicEvents.stream()
            .filter(QueryToolResultEvent.class::isInstance)
            .map(QueryToolResultEvent.class::cast))
            .anySatisfy(event -> {
                assertThat(event.toolCallId()).isEqualTo("shell-deny");
                assertThat(event.toolName()).isEqualTo("powershell");
                assertThat(event.isError()).isTrue();
                assertThat(event.summary())
                    .contains("Destructive git command denied")
                    .doesNotContain("SecurityException")
                    .doesNotContain("java.lang")
                    .doesNotContain("at com.clawcode");
            });
    }

    private boolean awaitCondition(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            try { Thread.sleep(50); } catch (InterruptedException e) { return false; }
        }
        return false;
    }

    private static String toolDesc(ModelRequest request, String name) {
        return request.tools().stream()
            .filter(t -> t.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Tool not found: " + name))
            .description();
    }

    private static com.clawcode.agent.forensics.AuditTrail noopAudit() {
        return event -> Mono.empty();
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // Best-effort cleanup for test temp files.
                    }
                });
        }
    }

    @FunctionalInterface
    interface BooleanSupplier {
        boolean getAsBoolean();
    }

    @TestConfiguration
    static class FailingOrchestrationConfig {

        @Bean
        @Primary
        ModelClient inspectableModelClient(SessionInspector inspector) {
            return request -> {
                inspector.capture(request);
                if (inspector.shouldFail()) {
                    return Flux.error(new RuntimeException("Simulated model failure"));
                }
                return inspector.responseFn().apply(request);
            };
        }

        @Bean
        SessionInspector sessionInspector() {
            return new SessionInspector();
        }

        @Bean
        ApiHookScenario apiHookScenario() {
            return new ApiHookScenario();
        }

        @Bean
        ToolExecutionHook apiRegressionHook(ApiHookScenario scenario) {
            return TestToolExecutionHook.builder()
                .preTool(scenario::preTool)
                .postTool(scenario::postTool)
                .build();
        }
    }

    static class ApiHookScenario {
        private volatile boolean denyFileWrite;
        private volatile boolean attachAfterFileList;

        void denyFileWriteAndAttachAfterFileList() {
            denyFileWrite = true;
            attachAfterFileList = true;
        }

        Mono<ToolPreHookResult> preTool(ToolPreHookContext context) {
            if (denyFileWrite && context.request().toolName().equals("file_write")) {
                return Mono.just(ToolPreHookResult.deny("api hook denied write", List.of()));
            }
            return Mono.just(ToolPreHookResult.continueWith(context.request(), context.messages()));
        }

        Mono<ToolPostHookResult> postTool(ToolPostHookContext context) {
            if (attachAfterFileList && context.request().toolName().equals("file_list")) {
                return Mono.just(ToolPostHookResult.continueWith(context.result(), List.of(
                    new UserMessage(UUID.randomUUID(), Instant.now(), "hook context from api post")
                )));
            }
            return Mono.just(ToolPostHookResult.continueWith(context.result(), context.messages()));
        }

        void reset() {
            denyFileWrite = false;
            attachAfterFileList = false;
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
