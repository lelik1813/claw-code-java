package com.clawcode.agent.core.query;

import com.clawcode.agent.core.prompt.SystemPromptBuilder;
import com.clawcode.agent.core.tasks.InMemoryTaskExecutor;
import com.clawcode.agent.core.tasks.TaskService;
import com.clawcode.agent.forensics.AuditEvent;
import com.clawcode.agent.forensics.AuditTrail;
import com.clawcode.agent.forensics.ObservabilityMetrics;
import com.clawcode.agent.model.*;
import com.clawcode.agent.mcp.*;
import com.clawcode.agent.persistence.InMemoryTranscriptStore;
import com.clawcode.agent.plugins.DefaultPluginToolFactory;
import com.clawcode.agent.plugins.PluginDescriptor;
import com.clawcode.agent.plugins.PluginRegistry;
import com.clawcode.agent.plugins.PluginToolDescriptor;
import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.AssistantTextBlock;
import com.clawcode.agent.shared.message.AssistantThinkingBlock;
import com.clawcode.agent.shared.message.AssistantToolUseBlock;
import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.ToolResultMessage;
import com.clawcode.agent.shared.message.UserMessage;
import com.clawcode.agent.skills.FileSystemSkillRegistry;
import com.clawcode.agent.skills.SkillContextService;
import com.clawcode.agent.skills.SkillsProperties;
import com.clawcode.agent.tools.*;
import com.clawcode.agent.tools.hooks.TestToolExecutionHook;
import com.clawcode.agent.tools.hooks.ToolExecutionHook;
import com.clawcode.agent.tools.hooks.ToolHookPipeline;
import com.clawcode.agent.tools.hooks.ToolPreHookResult;
import com.clawcode.agent.tools.hooks.ToolStopHookResult;
import com.clawcode.agent.tools.file.FileEditTool;
import com.clawcode.agent.tools.file.FileReadStateStore;
import com.clawcode.agent.tools.file.FileReadTool;
import com.clawcode.agent.tools.file.FileWriteTool;
import com.clawcode.agent.tools.mcp.McpReadResourceTool;
import com.clawcode.agent.tools.shell.PowerShellTool;
import com.clawcode.agent.tools.shell.PowerShellToolProperties;
import com.clawcode.agent.tools.task.TaskResultTool;
import com.clawcode.agent.tools.task.TaskStatusTool;
import com.clawcode.agent.tools.task.TaskSubmitTool;
import com.clawcode.agent.tools.web.WebFetchTool;
import com.clawcode.agent.tools.web.WebToolsProperties;
import com.clawcode.agent.tools.web.WebUrlGuard;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultQueryOrchestratorToolLoopTest {

    private String savedRoots;

    @BeforeEach
    void clearAllowedRoots() {
        savedRoots = System.getProperty("app.tools.allowed-roots");
        System.clearProperty("app.tools.allowed-roots");
    }

    @AfterEach
    void restoreAllowedRoots() {
        if (savedRoots != null) {
            System.setProperty("app.tools.allowed-roots", savedRoots);
        } else {
            System.clearProperty("app.tools.allowed-roots");
        }
    }

    private static final AuditTrail noopAudit = event -> Mono.empty();
    private static final ObservabilityMetrics noopMetrics =
        new ObservabilityMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    private static final SkillContextService noopSkills = new SkillContextService(null);
    private static final ToolRegistry noopRegistry = new ToolRegistry() {
        @Override public Optional<Tool> findByName(String name) { return Optional.empty(); }
        @Override public java.util.Set<String> listNames() { return java.util.Set.of(); }
    };

    @Test
    void resultEventOrderingNormalAnswer() {
        ModelClient modelClient = request -> Flux.just(
            new ModelStreamStartedEvent("glm-5.1"),
            new ModelTextDeltaEvent("final answer"),
            new ModelCompletedEvent()
        );
        ToolExecutor toolExecutor = (req, ctx) -> Mono.error(new AssertionError("No tools expected"));
        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit), noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
            "glm-5.1", null, List.of());

        List<QueryEvent> events = orchestrator.runTurn(command).collectList().block();
        assertThat(events).isNotNull();

        int finalTextIdx = indexOfEvent(events, e -> e instanceof QueryTextDeltaEvent d
            && d.text().equals("final answer"));
        int resultIdx = indexOfEvent(events, QueryResultEvent.class::isInstance);
        int completedIdx = indexOfEvent(events, QueryCompletedEvent.class::isInstance);

        assertThat(finalTextIdx).isGreaterThanOrEqualTo(0);
        assertThat(resultIdx).isGreaterThan(finalTextIdx);
        assertThat(completedIdx).isGreaterThan(resultIdx);
    }

    @Test
    void resultEventOrderingToolLoopAnswer() {
        AtomicInteger calls = new AtomicInteger();
        ModelClient modelClient = request -> {
            if (calls.getAndIncrement() == 0) {
                return Flux.just(
                    new ModelToolUseEvent("call-1", "echo", "x"),
                    new ModelCompletedEvent()
                );
            }
            return Flux.just(
                new ModelTextDeltaEvent("final answer"),
                new ModelCompletedEvent()
            );
        };
        ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
            ToolResult.success(req.toolCallId(), req.toolName(), "echo: " + req.input()));
        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit), noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
            "glm-5.1", null, List.of());

        List<QueryEvent> events = orchestrator.runTurn(command).collectList().block();
        assertThat(events).isNotNull();

        int finalTextIdx = indexOfEvent(events, e -> e instanceof QueryTextDeltaEvent d
            && d.text().equals("final answer"));
        int resultIdx = indexOfEvent(events, QueryResultEvent.class::isInstance);
        int completedIdx = indexOfEvent(events, QueryCompletedEvent.class::isInstance);

        assertThat(finalTextIdx).isGreaterThanOrEqualTo(0);
        assertThat(resultIdx).isGreaterThan(finalTextIdx);
        assertThat(completedIdx).isGreaterThan(resultIdx);
    }

    @Test
    void deepSeekThinkingBlockIsProviderOnlyAndPreservedForToolFollowUp() {
        AtomicInteger calls = new AtomicInteger();
        List<ModelRequest> capturedRequests = new ArrayList<>();
        ModelClient modelClient = request -> {
            capturedRequests.add(request);
            if (calls.getAndIncrement() == 0) {
                return Flux.just(
                    new ModelThinkingBlockEvent(new AssistantThinkingBlock("private reasoning", "sig-1")),
                    new ModelToolUseEvent("call-1", "echo", "x"),
                    new ModelStopReasonEvent("tool_use"),
                    new ModelCompletedEvent()
                );
            }
            return Flux.just(
                new ModelTextDeltaEvent("final answer"),
                new ModelCompletedEvent()
            );
        };
        ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
            ToolResult.success(req.toolCallId(), req.toolName(), "echo: " + req.input()));
        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
            "deepseek-v4-flash", null, List.of());

        List<QueryEvent> events = orchestrator.runTurn(command).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events.stream()
            .filter(QueryTextDeltaEvent.class::isInstance)
            .map(QueryTextDeltaEvent.class::cast)
            .map(QueryTextDeltaEvent::text))
            .noneMatch(text -> text.contains("private reasoning"));
        assertThat(events.stream()
            .filter(QueryErrorEvent.class::isInstance)
            .map(QueryErrorEvent.class::cast)
            .map(QueryErrorEvent::message))
            .noneMatch(message -> message.contains("private reasoning"));
        assertThat(events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(QueryTextDeltaEvent.class);
            assertThat(((QueryTextDeltaEvent) event).text()).isEqualTo("final answer");
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(QueryResultEvent.class);
            assertThat(((QueryResultEvent) event).success()).isTrue();
        });
        assertThat(events).anySatisfy(event -> assertThat(event).isInstanceOf(QueryCompletedEvent.class));

        assertThat(capturedRequests).hasSize(2);
        List<Message> secondRound = capturedRequests.get(1).messages();
        assertThat(secondRound).hasSize(3);
        assertThat(secondRound.get(1)).isInstanceOf(AssistantMessage.class);
        AssistantMessage assistant = (AssistantMessage) secondRound.get(1);
        assertThat(assistant.textContent()).isEmpty();
        assertThat(assistant.content()).hasSize(2);
        assertThat(assistant.content().get(0)).isInstanceOf(AssistantThinkingBlock.class);
        assertThat(((AssistantThinkingBlock) assistant.content().get(0)).thinking())
            .isEqualTo("private reasoning");
        assertThat(((AssistantThinkingBlock) assistant.content().get(0)).signature())
            .isEqualTo("sig-1");
        assertThat(assistant.content().get(1)).isInstanceOf(AssistantToolUseBlock.class);
        assertThat(secondRound.get(2)).isInstanceOf(ToolResultMessage.class);
    }

    @Test
    void resultEventOrderingModelErrorPath() {
        ModelClient modelClient = request -> Flux.just(
            new ModelStreamStartedEvent("glm-5.1"),
            new ModelTextDeltaEvent("partial"),
            new ModelErrorEvent("boom")
        );
        ToolExecutor toolExecutor = (req, ctx) -> Mono.error(new AssertionError("No tools expected"));
        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit), noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
            "glm-5.1", null, List.of());

        List<QueryEvent> events = orchestrator.runTurn(command).collectList().block();
        assertThat(events).isNotNull();

        int finalTextIdx = indexOfEvent(events, e -> e instanceof QueryTextDeltaEvent d
            && d.text().equals("partial"));
        int resultIdx = indexOfEvent(events, QueryResultEvent.class::isInstance);
        int terminalErrorIdx = indexOfEvent(events, e -> e instanceof QueryErrorEvent qe
            && "boom".equals(qe.message()));

        assertThat(finalTextIdx).isGreaterThanOrEqualTo(0);
        assertThat(terminalErrorIdx).isGreaterThan(finalTextIdx);
        assertThat(resultIdx).isGreaterThan(terminalErrorIdx);
        assertThat(events.stream().noneMatch(QueryCompletedEvent.class::isInstance)).isTrue();
    }

    @Test
    void providerStreamFailureEmitsQueryErrorAndResultInsteadOfThrowing() {
        ModelClient modelClient = request -> Flux.error(
            new RuntimeException("Anthropic API error: 400 BAD_REQUEST — {\"error\":\"raw body\"}"));
        ToolExecutor toolExecutor = (req, ctx) -> Mono.error(new AssertionError("No tools expected"));
        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
            "deepseek-v4-flash", null, List.of());

        List<QueryEvent> events = orchestrator.runTurn(command).collectList().block();

        assertThat(events).isNotNull();
        int errorIdx = indexOfEvent(events, QueryErrorEvent.class::isInstance);
        int resultIdx = indexOfEvent(events, QueryResultEvent.class::isInstance);
        assertThat(errorIdx).isGreaterThanOrEqualTo(0);
        assertThat(resultIdx).isGreaterThan(errorIdx);
        assertThat(events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(QueryResultEvent.class);
            assertThat(((QueryResultEvent) event).success()).isFalse();
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(QueryErrorEvent.class);
            QueryErrorEvent error = (QueryErrorEvent) event;
            assertThat(error.message()).isEqualTo("Anthropic API error: 400 BAD_REQUEST");
            assertThat(error.message()).doesNotContain("raw body");
            assertThat(error.code()).isEqualTo("model_error");
        });
        assertThat(events.stream().noneMatch(QueryCompletedEvent.class::isInstance)).isTrue();
    }

    @Test
    void resultEventOrderingMaxRoundPath() {
        ModelClient modelClient = request ->
            Flux.just(new ModelToolUseEvent("c1", "loop", null));
        ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
            ToolResult.success(req.toolCallId(), req.toolName(), "ok"));
        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills,
            new ToolPermissionProperties(false, ToolPermissionProperties.Mode.ALLOWLIST, null),
            new SystemPromptBuilder(), 1, 4);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
            "glm-5.1", null, List.of());

        List<QueryEvent> events = orchestrator.runTurn(command).collectList().block();
        assertThat(events).isNotNull();

        int finalTextIdx = indexOfEvent(events, e -> e instanceof QueryTextDeltaEvent d
            && d.text().contains("maximum tool rounds"));
        int stopReasonIdx = indexOfEvent(events, e -> e instanceof QueryStopReasonEvent s
            && "max_tool_rounds".equals(s.reason()));
        int resultIdx = indexOfEvent(events, QueryResultEvent.class::isInstance);
        int completedIdx = indexOfEvent(events, QueryCompletedEvent.class::isInstance);

        assertThat(finalTextIdx).isGreaterThanOrEqualTo(0);
        assertThat(stopReasonIdx).isGreaterThan(finalTextIdx);
        assertThat(resultIdx).isGreaterThan(stopReasonIdx);
        assertThat(completedIdx).isGreaterThan(resultIdx);
    }

    @Test
    void toolLoopEmitsCorrectEventSequence() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(
                    new ModelStreamStartedEvent("glm-5.1"),
                    new ModelToolUseEvent("call-1", "echo", "hello")
                );
            }
            return Flux.just(
                new ModelStreamStartedEvent("glm-5.1"),
                new ModelTextDeltaEvent("final answer"),
                new ModelCompletedEvent()
            );
        };

        ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
            ToolResult.success(req.toolCallId(), req.toolName(), "echo: " + req.input()));

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit), noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
            "glm-5.1", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            // round 1 — model events
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            // model emitted tool use -> orchestrator calls tool
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolCallId().equals("call-1") && r.toolName().equals("echo"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && r.toolCallId().equals("call-1")
                && r.toolName().equals("echo")
                && !r.isError()
                && r.summary().contains("echo: hello"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            // round 2 — model events after tool result
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                && d.text().equals("final answer"))
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();
    }

    @Test
    void longModelResponseDoesNotBuildRecursiveConcatChain() {
        List<ModelEvent> events = new ArrayList<>();
        events.add(new ModelStreamStartedEvent("glm-5.1"));
        for (int i = 0; i < 10_000; i++) {
            events.add(new ModelTextDeltaEvent("x"));
        }
        events.add(new ModelCompletedEvent());

        ModelClient modelClient = request -> Flux.fromIterable(events);
        ToolExecutor toolExecutor = (req, ctx) -> Mono.error(new AssertionError("No tools expected"));

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit), noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
            "glm-5.1", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .expectNextCount(10_000)
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();
    }

    @Test
    void completedEventAfterToolUseDoesNotFinishTurnBeforeToolRound() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(
                    new ModelStreamStartedEvent("glm-5.1"),
                    new ModelToolUseEvent("call-1", "echo", "hello"),
                    new ModelCompletedEvent()
                );
            }
            return Flux.just(
                new ModelTextDeltaEvent("final answer"),
                new ModelCompletedEvent()
            );
        };

        ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
            ToolResult.success(req.toolCallId(), req.toolName(), "echo: " + req.input()));

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit), noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
            "glm-5.1", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                && d.text().equals("final answer"))
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();

        assertThat(modelCalls.get()).isEqualTo(2);
    }

    @Test
    void multipleToolCallsInOneRoundProduceSingleFollowUpModelRequest() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<List<com.clawcode.agent.shared.message.Message>> secondRoundMessages =
            new AtomicReference<>();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(
                    new ModelTextDeltaEvent("checking "),
                    new ModelToolUseEvent("call-a", "echo", "a"),
                    new ModelToolUseEvent("call-b", "echo", "b"),
                    new ModelCompletedEvent()
                );
            }
            secondRoundMessages.set(request.messages());
            return Flux.just(new ModelCompletedEvent());
        };

        ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
            ToolResult.success(req.toolCallId(), req.toolName(), "echo: " + req.input()));

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit), noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
            "glm-5.1", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolCallId().equals("call-a"))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolCallId().equals("call-b"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && r.toolCallId().equals("call-a"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && r.toolCallId().equals("call-b"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();

        assertThat(modelCalls.get()).isEqualTo(2);
        assertThat(secondRoundMessages.get()).hasSize(4);
        assertThat(secondRoundMessages.get().get(0)).isInstanceOf(UserMessage.class);
        assertThat(secondRoundMessages.get().get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(secondRoundMessages.get().get(2)).isInstanceOf(ToolResultMessage.class);
        assertThat(secondRoundMessages.get().get(3)).isInstanceOf(ToolResultMessage.class);

        AssistantMessage assistant = (AssistantMessage) secondRoundMessages.get().get(1);
        assertThat(assistant.textContent()).isEqualTo("checking ");
        assertThat(assistant.content())
            .filteredOn(AssistantToolUseBlock.class::isInstance)
            .extracting(block -> ((AssistantToolUseBlock) block).id())
            .containsExactly("call-a", "call-b");
    }

    @Test
    void textDeltasSuppressedInToolUseRoundButPreservedInTranscript() {
        AtomicReference<List<com.clawcode.agent.shared.message.Message>> transcript =
            new AtomicReference<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(
                    new ModelTextDeltaEvent("let me look"),
                    new ModelToolUseEvent("call-1", "echo", "x"),
                    new ModelCompletedEvent()
                );
            }
            transcript.set(request.messages());
            return Flux.just(
                new ModelTextDeltaEvent("final answer"),
                new ModelCompletedEvent()
            );
        };

        ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
            ToolResult.success(req.toolCallId(), req.toolName(), "echo: " + req.input()));

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit), noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
            "glm-5.1", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            // tool-use round: no QueryTextDeltaEvent for "let me look"
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolCallId().equals("call-1"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && r.toolCallId().equals("call-1"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            // follow-up round: final text IS streamed
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                && d.text().equals("final answer"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();

        // transcript still has the suppressed text
        AssistantMessage assistant = (AssistantMessage) transcript.get().get(1);
        assertThat(assistant.textContent()).isEqualTo("let me look");
    }

    @Test
    void multiToolCallAssistantMessageContainsAllToolUsesOnce() {
        List<List<Message>> incrementalDeltas = new ArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(
                    new ModelTextDeltaEvent("checking"),
                    new ModelToolUseEvent("call-1", "echo", "a"),
                    new ModelToolUseEvent("call-2", "echo", "b"),
                    new ModelCompletedEvent()
                );
            }
            return Flux.just(
                new ModelTextDeltaEvent("all done"),
                new ModelCompletedEvent()
            );
        };

        ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
            ToolResult.success(req.toolCallId(), req.toolName(), "echo: " + req.input()));

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit), noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
            "glm-5.1", null, List.of());

        List<QueryEvent> events = orchestrator.runTurn(command).collectList().block();
        assertThat(events).isNotNull();
        events.stream()
            .filter(QueryTranscriptUpdateEvent.class::isInstance)
            .map(QueryTranscriptUpdateEvent.class::cast)
            .forEach(u -> incrementalDeltas.add(u.update().messagesToPersist()));

        List<Message> persisted = incrementalDeltas.stream()
            .flatMap(List::stream)
            .toList();
        assertThat(persisted).hasSize(4);

        // Index 0: assistant message with text + both tool_use blocks in order
        assertThat(persisted.get(0)).isInstanceOf(AssistantMessage.class);
        AssistantMessage assistantMsg = (AssistantMessage) persisted.get(0);
        assertThat(assistantMsg.content()).hasSize(3);
        assertThat(assistantMsg.content().get(0)).isInstanceOf(AssistantTextBlock.class);
        assertThat(((AssistantTextBlock) assistantMsg.content().get(0)).text()).isEqualTo("checking");
        assertThat(assistantMsg.content().get(1)).isInstanceOf(AssistantToolUseBlock.class);
        AssistantToolUseBlock firstToolUse = (AssistantToolUseBlock) assistantMsg.content().get(1);
        assertThat(firstToolUse.id()).isEqualTo("call-1");
        assertThat(firstToolUse.name()).isEqualTo("echo");
        assertThat(firstToolUse.input()).isEqualTo("a");
        assertThat(assistantMsg.content().get(2)).isInstanceOf(AssistantToolUseBlock.class);
        AssistantToolUseBlock secondToolUse = (AssistantToolUseBlock) assistantMsg.content().get(2);
        assertThat(secondToolUse.id()).isEqualTo("call-2");
        assertThat(secondToolUse.name()).isEqualTo("echo");
        assertThat(secondToolUse.input()).isEqualTo("b");

        // Index 1: tool result for call-1
        assertThat(persisted.get(1)).isInstanceOf(ToolResultMessage.class);
        assertThat(((ToolResultMessage) persisted.get(1)).toolCallId()).isEqualTo("call-1");

        // Index 2: tool result for call-2
        assertThat(persisted.get(2)).isInstanceOf(ToolResultMessage.class);
        assertThat(((ToolResultMessage) persisted.get(2)).toolCallId()).isEqualTo("call-2");

        // Index 3: final assistant text
        assertThat(persisted.get(3)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) persisted.get(3)).textContent()).isEqualTo("all done");
    }

    @Test
    void multiRoundToolLoopCarriesFullHistoryToFinalModelRequest() {
        AtomicReference<List<com.clawcode.agent.shared.message.Message>> round2Messages =
            new AtomicReference<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(
                    new ModelToolUseEvent("call-1", "echo", "x"),
                    new ModelCompletedEvent()
                );
            }
            if (call == 1) {
                return Flux.just(
                    new ModelToolUseEvent("call-2", "echo", "y"),
                    new ModelCompletedEvent()
                );
            }
            round2Messages.set(request.messages());
            return Flux.just(
                new ModelTextDeltaEvent("all done"),
                new ModelCompletedEvent()
            );
        };

        ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
            ToolResult.success(req.toolCallId(), req.toolName(), "echo: " + req.input()));

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit), noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
            "glm-5.1", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            // round 0: tool use
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolCallId().equals("call-1"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            // round 1: tool use
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolCallId().equals("call-2"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            // round 2: final text
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                && d.text().equals("all done"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();

        // round 2 model request sees full history including both assistant + tool results
        assertThat(round2Messages.get()).hasSize(5);
        assertThat(round2Messages.get().get(0)).isInstanceOf(UserMessage.class);
        assertThat(round2Messages.get().get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(round2Messages.get().get(2)).isInstanceOf(ToolResultMessage.class);
        assertThat(round2Messages.get().get(3)).isInstanceOf(AssistantMessage.class);
        assertThat(round2Messages.get().get(4)).isInstanceOf(ToolResultMessage.class);

        assertThat(modelCalls.get()).isEqualTo(3);
    }

    @Test
    void allowlistFiltersAdvertisedToolDefinitions() {
        AtomicReference<List<ModelToolDefinition>> advertisedTools = new AtomicReference<>();
        ModelClient modelClient = request -> {
            advertisedTools.set(request.tools());
            return Flux.just(new ModelCompletedEvent());
        };

        ToolRegistry registry = new SpringToolRegistry(List.of(
            stubTool("file_read"),
            stubTool("powershell")
        ));
        ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
            ToolResult.success(req.toolCallId(), req.toolName(), "unused"));

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, registry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills,
            new ToolPermissionProperties(true, ToolPermissionProperties.Mode.ALLOWLIST,
                java.util.Set.of("file_read")),
            new SystemPromptBuilder(), 10);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
            "glm-5.1", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();

        assertThat(advertisedTools.get())
            .extracting(ModelToolDefinition::name)
            .containsExactly("file_read");
    }

    @Test
    void denylistFiltersAdvertisedToolDefinitions() {
        AtomicReference<List<ModelToolDefinition>> advertisedTools = new AtomicReference<>();
        ModelClient modelClient = request -> {
            advertisedTools.set(request.tools());
            return Flux.just(new ModelCompletedEvent());
        };

        ToolRegistry registry = new SpringToolRegistry(List.of(
            stubTool("file_read"),
            stubTool("powershell")
        ));
        ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
            ToolResult.success(req.toolCallId(), req.toolName(), "unused"));

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, registry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills,
            new ToolPermissionProperties(true, ToolPermissionProperties.Mode.DENYLIST,
                java.util.Set.of("powershell")),
            new SystemPromptBuilder(), 10);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
            "glm-5.1", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();

        assertThat(advertisedTools.get())
            .extracting(ModelToolDefinition::name)
            .containsExactly("file_read");
    }

    @Test
    void powershellMavenCommandDeniedWhenNotAdvertised() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger shellExecutions = new AtomicInteger();
        AtomicReference<List<ModelToolDefinition>> advertisedTools = new AtomicReference<>();
        ModelClient modelClient = request -> {
            advertisedTools.compareAndSet(null, request.tools());
            if (modelCalls.getAndIncrement() == 0) {
                return Flux.just(
                    new ModelToolUseEvent("build-call", "powershell", ".\\mvnw.cmd -q test"),
                    new ModelCompletedEvent());
            }
            return Flux.just(new ModelTextDeltaEvent("no shell"), new ModelCompletedEvent());
        };

        Tool powershell = toolWithDefinition(new PowerShellTool(new PowerShellToolProperties(30)).definition(), input -> {
            shellExecutions.incrementAndGet();
            return Mono.just("should not run");
        });
        ToolRegistry registry = new SpringToolRegistry(List.of(stubTool("file_read"), powershell));
        var policy = new ConfigurableToolPermissionPolicy(new ToolPermissionProperties(
            true, ToolPermissionProperties.Mode.ALLOWLIST, java.util.Set.of("file_read")));
        DefaultToolExecutor executor = new DefaultToolExecutor(registry, policy, noopAudit, noopMetrics, List.of());

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, executor, registry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills,
            new ToolPermissionProperties(true, ToolPermissionProperties.Mode.ALLOWLIST,
                java.util.Set.of("file_read")),
            new SystemPromptBuilder(), 10);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "run build")),
            "glm-5.1", null, List.of());

        List<QueryEvent> events = orchestrator.runTurn(command).collectList().block();

        assertThat(events).isNotNull();
        assertThat(advertisedTools.get())
            .extracting(ModelToolDefinition::name)
            .containsExactly("file_read");
        assertThat(events).filteredOn(QueryToolResultEvent.class::isInstance)
            .map(QueryToolResultEvent.class::cast)
            .anySatisfy(result -> {
                assertThat(result.toolName()).isEqualTo("powershell");
                assertThat(result.isError()).isTrue();
                assertThat(result.summary()).contains("Tool 'powershell' is denied");
            });
        assertThat(shellExecutions).hasValue(0);
    }

    @Test
    void powershellMavenCommandAvailableWhenAdvertised() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger shellExecutions = new AtomicInteger();
        AtomicReference<Object> shellInput = new AtomicReference<>();
        AtomicReference<List<ModelToolDefinition>> advertisedTools = new AtomicReference<>();
        ModelClient modelClient = request -> {
            advertisedTools.compareAndSet(null, request.tools());
            if (modelCalls.getAndIncrement() == 0) {
                return Flux.just(
                    new ModelToolUseEvent("build-call", "powershell", ".\\mvnw.cmd -q test"),
                    new ModelCompletedEvent());
            }
            return Flux.just(new ModelTextDeltaEvent("build done"), new ModelCompletedEvent());
        };

        Tool powershell = toolWithDefinition(new PowerShellTool(new PowerShellToolProperties(30)).definition(), input -> {
            shellExecutions.incrementAndGet();
            shellInput.set(input);
            return Mono.just("build ok");
        });
        ToolRegistry registry = new SpringToolRegistry(List.of(stubTool("file_read"), powershell));
        var permissionProperties = new ToolPermissionProperties(
            true, ToolPermissionProperties.Mode.ALLOWLIST, java.util.Set.of("file_read", "powershell"));
        var policy = new ConfigurableToolPermissionPolicy(permissionProperties);
        DefaultToolExecutor executor = new DefaultToolExecutor(registry, policy, noopAudit, noopMetrics, List.of());

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, executor, registry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills,
            permissionProperties, new SystemPromptBuilder(), 10);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "run build")),
            "glm-5.1", null, List.of());

        List<QueryEvent> events = orchestrator.runTurn(command).collectList().block();

        assertThat(events).isNotNull();
        assertThat(advertisedTools.get())
            .filteredOn(tool -> tool.name().equals("powershell"))
            .singleElement()
            .satisfies(tool -> assertThat(tool.description()).contains("mvnw.cmd"));
        assertThat(events).filteredOn(QueryToolResultEvent.class::isInstance)
            .map(QueryToolResultEvent.class::cast)
            .anySatisfy(result -> {
                assertThat(result.toolName()).isEqualTo("powershell");
                assertThat(result.isError()).isFalse();
                assertThat(result.summary()).contains("build ok");
            });
        assertThat(shellExecutions).hasValue(1);
        assertThat(shellInput.get()).isEqualTo(".\\mvnw.cmd -q test");
    }

    @Test
    void toolLoopEnrichesHistoryWithToolResultMessage() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(new ModelToolUseEvent("c1", "t", null));
            }
            // second call should have ToolResultMessage in history
            long toolResults = request.messages().stream()
                .filter(m -> m instanceof com.clawcode.agent.shared.message.ToolResultMessage)
                .count();
            if (toolResults != 1) {
                return Flux.just(new ModelErrorEvent("expected 1 tool result, got " + toolResults));
            }
            return Flux.just(new ModelCompletedEvent());
        };

        ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
            ToolResult.success(req.toolCallId(), req.toolName(), "ok"));

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit), noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "go")),
            "m", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();
    }

    @Test
    void hookContextMessagesArePersistedAfterToolResultBeforeNextRound() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<List<Message>> secondRoundMessages = new AtomicReference<>();
        UserMessage hookContext = new UserMessage(
            UUID.randomUUID(), Instant.now(), "hook context for model");

        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(new ModelToolUseEvent("c1", "t", null));
            }
            secondRoundMessages.set(request.messages());
            return Flux.just(
                new ModelTextDeltaEvent("final answer"),
                new ModelCompletedEvent());
        };

        ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
            ToolResult.success(req.toolCallId(), req.toolName(), "ok", List.of(hookContext)));

        var transcriptStore = new InMemoryTranscriptStore(noopAudit);
        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, transcriptStore,
            noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "go")),
            "m", null, List.of());

        List<Message> transcriptMessages = new ArrayList<>();
        List<QueryEvent> events = orchestrator.runTurn(command).collectList().block();
        assertThat(events).isNotNull();
        events.stream()
            .filter(QueryTranscriptUpdateEvent.class::isInstance)
            .map(QueryTranscriptUpdateEvent.class::cast)
            .flatMap(e -> e.update().messagesToPersist().stream())
            .forEach(transcriptMessages::add);

        assertThat(secondRoundMessages.get()).isNotNull();
        assertThat(secondRoundMessages.get()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(secondRoundMessages.get().get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(secondRoundMessages.get().get(2)).isInstanceOf(ToolResultMessage.class);
        assertThat(secondRoundMessages.get().get(3)).isSameAs(hookContext);

        assertThat(transcriptMessages).hasSizeGreaterThanOrEqualTo(4);
        assertThat(transcriptMessages.get(0)).isInstanceOf(AssistantMessage.class);
        assertThat(transcriptMessages.get(1)).isInstanceOf(ToolResultMessage.class);
        assertThat(transcriptMessages.get(2)).isSameAs(hookContext);
        assertThat(transcriptMessages.get(3)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) transcriptMessages.get(3)).textContent()).isEqualTo("final answer");

        long publicHookTextEvents = events.stream()
            .filter(QueryTextDeltaEvent.class::isInstance)
            .map(QueryTextDeltaEvent.class::cast)
            .filter(e -> e.text().equals("hook context for model"))
            .count();
        assertThat(publicHookTextEvents).isZero();
    }

    @Test
    void stopHookRetryAddsModelOnlyContextAndRunsNextRound() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<List<Message>> secondRequestMessages = new AtomicReference<>();
        UserMessage retryContext = new UserMessage(
            UUID.randomUUID(), Instant.now(), "resume directly");
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(
                    new ModelTextDeltaEvent("stopped draft"),
                    new ModelStopReasonEvent("end_turn"),
                    new ModelCompletedEvent());
            }
            secondRequestMessages.set(request.messages());
            assertThat(request.messages()).contains(retryContext);
            return Flux.just(
                new ModelTextDeltaEvent("final answer"),
                new ModelCompletedEvent());
        };
        ToolExecutionHook hook = TestToolExecutionHook.builder()
            .stop(context -> Mono.just(modelCalls.get() == 1
                    ? ToolStopHookResult.retry(List.of(retryContext))
                    : ToolStopHookResult.continueDefault()))
            .build();
        var orchestrator = orchestratorWithStopHook(modelClient, hook);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "go")),
            "m", null, List.of());

        List<QueryEvent> events = orchestrator.runTurn(command).collectList().block();

        assertThat(events).isNotNull();
        assertThat(modelCalls.get()).isEqualTo(2);
        assertThat(secondRequestMessages.get()).contains(retryContext);
        assertThat(events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(QueryTextDeltaEvent.class);
            assertThat(((QueryTextDeltaEvent) event).text()).isEqualTo("final answer");
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(QueryResultEvent.class);
            assertThat(((QueryResultEvent) event).success()).isTrue();
        });
        assertThat(events.stream()
            .filter(QueryTextDeltaEvent.class::isInstance)
            .map(QueryTextDeltaEvent.class::cast)
            .map(QueryTextDeltaEvent::text))
            .containsExactly("final answer");

        List<Message> transcriptMessages = events.stream()
            .filter(QueryTranscriptUpdateEvent.class::isInstance)
            .map(QueryTranscriptUpdateEvent.class::cast)
            .flatMap(event -> event.update().messagesToPersist().stream())
            .toList();
        assertThat(transcriptMessages)
            .filteredOn(AssistantMessage.class::isInstance)
            .singleElement()
            .satisfies(message -> assertThat(((AssistantMessage) message).textContent())
                .isEqualTo("final answer"));
        assertThat(transcriptMessages)
            .noneMatch(message -> message == retryContext)
            .noneMatch(message -> message instanceof AssistantMessage assistant
                && assistant.textContent().contains("stopped draft"));
    }

    @Test
    void stopHookFailEmitsFailureResult() {
        ModelClient modelClient = request -> Flux.just(
            new ModelStopReasonEvent("end_turn"),
            new ModelCompletedEvent());
        ToolExecutionHook hook = TestToolExecutionHook.builder()
            .stop(context -> Mono.just(ToolStopHookResult.fail(
                "stop hook failure", "hook_stop", List.of())))
            .build();
        var orchestrator = orchestratorWithStopHook(modelClient, hook);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "go")),
            "m", null, List.of());

        List<QueryEvent> events = orchestrator.runTurn(command).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(QueryTextDeltaEvent.class);
            assertThat(((QueryTextDeltaEvent) event).text()).isEqualTo("stop hook failure");
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(QueryResultEvent.class);
            QueryResultEvent result = (QueryResultEvent) event;
            assertThat(result.success()).isFalse();
            assertThat(result.stopReason()).isEqualTo("hook_stop");
        });
    }

    @Test
    void stopHookRetryLimitProducesCleanFailure() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            modelCalls.incrementAndGet();
            return Flux.just(
                new ModelStopReasonEvent("end_turn"),
                new ModelCompletedEvent());
        };
        ToolExecutionHook hook = TestToolExecutionHook.builder()
            .stop(context -> Mono.just(ToolStopHookResult.retry(List.of())))
            .build();
        var orchestrator = orchestratorWithStopHook(modelClient, hook, 2);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "go")),
            "m", null, List.of());

        List<QueryEvent> events = orchestrator.runTurn(command).collectList().block();

        assertThat(events).isNotNull();
        assertThat(modelCalls.get()).isEqualTo(2);
        assertThat(events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(QueryTextDeltaEvent.class);
            assertThat(((QueryTextDeltaEvent) event).text()).isEqualTo("Stop hook retry limit reached.");
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(QueryResultEvent.class);
            QueryResultEvent result = (QueryResultEvent) event;
            assertThat(result.success()).isFalse();
            assertThat(result.stopReason()).isEqualTo("hook_stop_retry_limit");
        });
    }

    @Test
    void largeToolResultIsBudgetedBeforeSecondModelRoundHistory() {
        String middle = "OMITTED-MIDDLE-UNIQUE";
        String largeOutput = "HEAD-" + "a".repeat(7000) + middle + "b".repeat(7000) + "-TAIL";
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<List<Message>> secondRoundMessages = new AtomicReference<>();

        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(new ModelToolUseEvent(
                    "read-large", "file_read", Map.of("path", "src/Large.java")));
            }
            secondRoundMessages.set(request.messages());
            return Flux.just(
                new ModelTextDeltaEvent("done"),
                new ModelCompletedEvent());
        };

        ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
            ToolResult.success(req.toolCallId(), req.toolName(), largeOutput));

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "read large")),
            "m", null, List.of());

        List<QueryToolResultEvent> toolResultEvents = new ArrayList<>();
        List<QueryToolUseSummaryEvent> summaries = new ArrayList<>();

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> {
                if (e instanceof QueryToolResultEvent tr) {
                    toolResultEvents.add(tr);
                    return tr.summary().contains("[tool result compacted]")
                        && !tr.summary().contains(middle);
                }
                return false;
            })
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> {
                if (e instanceof QueryToolUseSummaryEvent s) {
                    summaries.add(s);
                    return s.totalToolCalls() == 1
                        && s.compactedResults() == 1
                        && s.errorResults() == 0
                        && s.paths().contains("src/Large.java")
                        && s.summary().contains("total_tool_calls: 1");
                }
                return false;
            })
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d && d.text().equals("done"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();

        assertThat(toolResultEvents).hasSize(1);
        assertThat(summaries).hasSize(1);
        ToolResultMessage msg = secondRoundMessages.get().stream()
            .filter(m -> m instanceof ToolResultMessage)
            .map(m -> (ToolResultMessage) m)
            .findFirst()
            .orElseThrow();

        assertThat(msg.content()).contains("[tool result compacted]");
        assertThat(msg.content()).contains("tool: file_read");
        assertThat(msg.content()).contains("tool_call_id: read-large");
        assertThat(msg.content()).contains("path: src/Large.java");
        assertThat(msg.content()).contains("original_chars: " + largeOutput.length());
        assertThat(msg.content()).doesNotContain(middle);
        assertThat(msg.content()).doesNotContain(largeOutput);

        List<Message> secondMessages = secondRoundMessages.get();
        int toolResultIndex = secondMessages.indexOf(msg);
        assertThat(secondMessages.get(toolResultIndex + 1)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) secondMessages.get(toolResultIndex + 1)).content())
            .contains("[tool batch summary]")
            .contains("total_tool_calls: 1")
            .contains("paths: src/Large.java")
            .doesNotContain(middle);
    }

    @Test
    void oneSmallToolResultDoesNotEmitBatchSummary() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<List<Message>> secondRoundMessages = new AtomicReference<>();

        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(new ModelToolUseEvent(
                    "read-small", "file_read", Map.of("path", "src/Small.java")));
            }
            secondRoundMessages.set(request.messages());
            return Flux.just(new ModelTextDeltaEvent("done"), new ModelCompletedEvent());
        };

        ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
            ToolResult.success(req.toolCallId(), req.toolName(), "small output"));

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "read small")),
            "m", null, List.of());

        List<QueryEvent> events = new ArrayList<>();

        StepVerifier.create(orchestrator.runTurn(command))
            .thenConsumeWhile(e -> {
                events.add(e);
                return true;
            })
            .verifyComplete();

        assertThat(events).noneMatch(QueryToolUseSummaryEvent.class::isInstance);
        assertThat(secondRoundMessages.get()).noneMatch(m ->
            m instanceof UserMessage u && u.content().contains("[tool batch summary]"));
    }

    @Test
    void fourSmallToolResultsEmitBatchSummaryBeforeSecondModelRound() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<List<Message>> secondRoundMessages = new AtomicReference<>();

        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(
                    new ModelToolUseEvent("c1", "file_read", Map.of("path", "src/A.java")),
                    new ModelToolUseEvent("c2", "file_read", Map.of("path", "src/B.java")),
                    new ModelToolUseEvent("c3", "file_read", Map.of("path", "src/C.java")),
                    new ModelToolUseEvent("c4", "file_read", Map.of("path", "src/D.java")));
            }
            secondRoundMessages.set(request.messages());
            return Flux.just(new ModelTextDeltaEvent("done"), new ModelCompletedEvent());
        };

        ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
            ToolResult.success(req.toolCallId(), req.toolName(), "small:" + req.toolCallId()));

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "read four")),
            "m", null, List.of());

        List<QueryEvent> events = new ArrayList<>();

        StepVerifier.create(orchestrator.runTurn(command))
            .thenConsumeWhile(e -> {
                events.add(e);
                return true;
            })
            .verifyComplete();

        List<QueryToolResultEvent> toolResults = events.stream()
            .filter(QueryToolResultEvent.class::isInstance)
            .map(QueryToolResultEvent.class::cast)
            .toList();
        QueryToolUseSummaryEvent summary = events.stream()
            .filter(QueryToolUseSummaryEvent.class::isInstance)
            .map(QueryToolUseSummaryEvent.class::cast)
            .findFirst()
            .orElseThrow();

        assertThat(toolResults).extracting(QueryToolResultEvent::toolCallId)
            .containsExactly("c1", "c2", "c3", "c4");
        assertThat(summary.totalToolCalls()).isEqualTo(4);
        assertThat(summary.compactedResults()).isZero();
        assertThat(summary.errorResults()).isZero();
        assertThat(summary.paths()).containsExactly("src/A.java", "src/B.java", "src/C.java", "src/D.java");
        assertThat(events.indexOf(summary)).isGreaterThan(events.lastIndexOf(toolResults.get(3)));

        List<Message> messages = secondRoundMessages.get();
        List<ToolResultMessage> toolResultMessages = messages.stream()
            .filter(ToolResultMessage.class::isInstance)
            .map(ToolResultMessage.class::cast)
            .toList();
        assertThat(toolResultMessages).extracting(ToolResultMessage::toolCallId)
            .containsExactly("c1", "c2", "c3", "c4");
        int lastToolResultIndex = messages.lastIndexOf(toolResultMessages.get(3));
        assertThat(messages.get(lastToolResultIndex + 1)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) messages.get(lastToolResultIndex + 1)).content())
            .contains("[tool batch summary]")
            .contains("total_tool_calls: 4")
            .contains("paths: src/A.java, src/B.java, src/C.java, src/D.java");
    }

    @Test
    void toolLoopStopsAtMaxRounds() {
        ModelClient modelClient = request ->
            Flux.just(new ModelToolUseEvent("c1", "loop", null));

        ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
            ToolResult.success(req.toolCallId(), req.toolName(), "ok"));

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit), noopAudit, noopMetrics, noopSkills, 2);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "go")),
            "m", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            // round 1
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            // round 2
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            // round 3 -- exceeded limit, clean stop with explanation
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent
                && ((QueryTextDeltaEvent) e).text().contains("maximum tool rounds"))
            .expectNextMatches(e -> e instanceof QueryStopReasonEvent
                && ((QueryStopReasonEvent) e).reason().equals("max_tool_rounds"))
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent r
                && !r.success()
                && "max_tool_rounds".equals(r.stopReason())
                && r.numTurns() == 2
                && r.permissionDenials() == 0
                && r.durationMs() >= 0)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();
    }

    @Test
    void toolErrorDoesNotBreakLoop() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(new ModelToolUseEvent("c1", "bad", null));
            }
            return Flux.just(
                new ModelStreamStartedEvent("m"),
                new ModelTextDeltaEvent("recovered"),
                new ModelCompletedEvent());
        };

        ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
            ToolResult.error(req.toolCallId(), req.toolName(), "tool failed"));

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit), noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "go")),
            "m", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && r.isError() && r.summary().equals("tool failed"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                && d.text().equals("recovered"))
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();
    }

    @Test
    void realFileReadToolLoop() throws IOException {
        Path dir = Path.of("target").resolve("tool-loop-e2e");
        Files.createDirectories(dir);
        Path testFile = dir.resolve("data.txt");
        Files.writeString(testFile, "e2e-content");

        try {
            String filePath = testFile.toString();
            AtomicInteger modelCalls = new AtomicInteger();
            ModelClient modelClient = request -> {
                int call = modelCalls.getAndIncrement();
                if (call == 0) {
                    return Flux.just(new ModelToolUseEvent(
                        "call-real", "file_read", Map.of("path", filePath)));
                }
                return Flux.just(
                    new ModelStreamStartedEvent("m"),
                    new ModelTextDeltaEvent("done"),
                    new ModelCompletedEvent());
            };

            SpringToolRegistry registry = new SpringToolRegistry(List.of(
                new FileReadTool(new com.clawcode.agent.tools.file.FileReadStateStore())));
            DefaultToolExecutor executor = new DefaultToolExecutor(
                registry, (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow()), noopAudit, noopMetrics, List.of());

            var orchestrator = new DefaultQueryOrchestrator(
                modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit), noopAudit, noopMetrics, noopSkills);

            var command = new TurnCommand(
                UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "read it")),
                "m", null, List.of());

            StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                    && r.toolName().equals("file_read")
                    && r.toolCallId().equals("call-real"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                    && !r.isError()
                    && r.summary().contains("e2e-content"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                    && d.text().equals("done"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();
        } finally {
            Files.deleteIfExists(testFile);
        }
    }

    @Test
    void deniedToolInLoopRecoversWithNextModelRound() throws IOException {
        Path evilPath = Path.of("target/evil.txt");
        Files.deleteIfExists(evilPath);

        AtomicReference<List<com.clawcode.agent.shared.message.Message>> round1Messages =
            new AtomicReference<>();
        List<List<Message>> transcriptUpdates = new ArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(new ModelToolUseEvent("c-denied", "file_write",
                    Map.of("path", "target/evil.txt", "content", "hacked")));
            }
            round1Messages.set(request.messages());
            return Flux.just(
                new ModelStreamStartedEvent("m"),
                new ModelTextDeltaEvent("recovered after deny"),
                new ModelCompletedEvent());
        };

        ToolPermissionPolicy denyWrite = (req, ctx) -> {
            if ("file_write".equals(req.toolName())) {
                return Mono.just(new ToolPermissionDecision.Deny("writes not allowed"));
            }
            return Mono.just(new ToolPermissionDecision.Allow());
        };

        SpringToolRegistry registry = new SpringToolRegistry(List.of(
            new com.clawcode.agent.tools.file.FileWriteTool(
                new com.clawcode.agent.tools.file.FileReadStateStore())));
        DefaultToolExecutor executor = new DefaultToolExecutor(registry, denyWrite, noopAudit, noopMetrics, List.of());

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit), noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "write something")),
            "m", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("file_write")
                && r.toolCallId().equals("c-denied"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && r.isError()
                && r.summary().contains("Do not retry"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                && d.text().equals("recovered after deny"))
            .expectNextMatches(e -> {
                if (e instanceof QueryTranscriptUpdateEvent u) {
                    transcriptUpdates.add(u.update().messagesToPersist());
                    return true;
                }
                return false;
            })
            .expectNextMatches(e -> e instanceof QueryResultEvent r
                && r.permissionDenials() == 1)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();

        assertThat(round1Messages.get()).isNotNull();
        ToolResultMessage deniedMsg = (ToolResultMessage) round1Messages.get().stream()
            .filter(m -> m instanceof ToolResultMessage)
            .map(m -> (ToolResultMessage) m)
            .filter(m -> "file_write".equals(m.toolName()))
            .findFirst().orElse(null);
        assertThat(deniedMsg).isNotNull();
        assertThat(deniedMsg.toolCallId()).isEqualTo("c-denied");
        assertThat(deniedMsg.isError()).isTrue();
        assertThat(deniedMsg.content()).contains("Do not retry");

        assertThat(Files.exists(evilPath))
            .as("denied file_write must not create the file on disk")
            .isFalse();
    }

    @Test
    void preHookDeniedToolInLoopIsModelVisibleWithoutFileSideEffects() throws IOException {
        Path deniedPath = Path.of("target/pre-hook-denied.txt");
        Files.deleteIfExists(deniedPath);

        AtomicReference<List<Message>> secondRoundMessages = new AtomicReference<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(new ModelToolUseEvent("c-pre-deny", "file_write",
                    Map.of("path", deniedPath.toString(), "content", "blocked")));
            }
            secondRoundMessages.set(request.messages());
            ToolResultMessage deniedResult = request.messages().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .filter(m -> "c-pre-deny".equals(m.toolCallId()))
                .findFirst()
                .orElse(null);
            if (deniedResult == null || !deniedResult.isError()
                || !deniedResult.content().contains("denied by hook")
                || !deniedResult.content().contains("Do not retry")) {
                return Flux.just(new ModelErrorEvent("expected hook-denied ToolResultMessage in history"));
            }
            return Flux.just(
                new ModelStreamStartedEvent("m"),
                new ModelTextDeltaEvent(
                    "I cannot write that file because the hook denied the tool call."),
                new ModelCompletedEvent());
        };

        ToolExecutionHook hook = TestToolExecutionHook.builder()
            .preTool(context -> Mono.just(ToolPreHookResult.deny("write blocked by pre-hook", List.of())))
            .build();
        SpringToolRegistry registry = new SpringToolRegistry(List.of(
            new FileWriteTool(new FileReadStateStore())));
        DefaultToolExecutor executor = new DefaultToolExecutor(
            registry, (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow()),
            noopAudit, noopMetrics, List.of(hook));

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "write blocked content")),
            "m", null, List.of());

        try {
            StepVerifier.create(orchestrator.runTurn(command))
                .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                    && r.toolName().equals("file_write")
                    && r.toolCallId().equals("c-pre-deny"))
                .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
                .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                    && r.isError()
                    && r.summary().contains("denied by hook")
                    && r.summary().contains("Do not retry"))
                .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
                .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
                .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
                .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                    && d.text().contains("hook denied"))
                .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
                .expectNextMatches(e -> e instanceof QueryResultEvent r
                    && r.success()
                    && r.permissionDenials() == 1)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            assertThat(secondRoundMessages.get()).isNotNull();
            ToolResultMessage deniedMsg = secondRoundMessages.get().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .filter(m -> "c-pre-deny".equals(m.toolCallId()))
                .findFirst()
                .orElse(null);
            assertThat(deniedMsg).isNotNull();
            assertThat(deniedMsg.isError()).isTrue();
            assertThat(deniedMsg.content())
                .contains("Tool 'file_write' is denied by hook")
                .contains("write blocked by pre-hook")
                .contains("Do not retry");
            assertThat(modelCalls.get()).isEqualTo(2);
            assertThat(Files.exists(deniedPath))
                .as("pre-hook denied file_write must not create or modify the file")
                .isFalse();
        } finally {
            Files.deleteIfExists(deniedPath);
        }
    }

    @Test
    void deniedToolFollowUpRoundExplainsLimitation() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(new ModelToolUseEvent("c-deny", "file_write",
                    Map.of("path", "target/out.txt", "content", "data")));
            }
            long deniedToolResults = request.messages().stream()
                .filter(m -> m instanceof ToolResultMessage)
                .map(m -> (ToolResultMessage) m)
                .filter(m -> m.isError() && m.content().contains("Do not retry"))
                .count();
            if (deniedToolResults == 0) {
                return Flux.just(new ModelErrorEvent("expected denied ToolResultMessage in history"));
            }
            return Flux.just(
                new ModelStreamStartedEvent("m"),
                new ModelTextDeltaEvent(
                    "I cannot write files in the current tool mode. I can provide a patch instead."),
                new ModelCompletedEvent());
        };

        ToolPermissionPolicy denyWrite = (req, ctx) -> {
            if ("file_write".equals(req.toolName())) {
                return Mono.just(new ToolPermissionDecision.Deny("writes not allowed"));
            }
            return Mono.just(new ToolPermissionDecision.Allow());
        };

        SpringToolRegistry registry = new SpringToolRegistry(List.of(
            new FileWriteTool(new com.clawcode.agent.tools.file.FileReadStateStore())));
        DefaultToolExecutor executor = new DefaultToolExecutor(registry, denyWrite, noopAudit, noopMetrics, List.of());

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "write to a file")),
            "m", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("file_write")
                && r.toolCallId().equals("c-deny"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && r.isError())
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                && d.text().contains("I cannot write files"))
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();

        assertThat(modelCalls.get()).isEqualTo(2);
    }

    @Test
    void deniedWriteWithoutPriorReadInTranscriptProducesToolResultError() throws IOException {
        Path dir = Path.of("target").resolve("write-no-read-orch");
        Files.createDirectories(dir);
        Path file = dir.resolve("existing.txt");
        Files.writeString(file, "original content");

        try {
            String filePath = file.toString();
            AtomicReference<List<com.clawcode.agent.shared.message.Message>> round1Messages =
                new AtomicReference<>();
            AtomicInteger modelCalls = new AtomicInteger();
            ModelClient modelClient = request -> {
                int call = modelCalls.getAndIncrement();
                if (call == 0) {
                    return Flux.just(new ModelToolUseEvent("c-write", "file_write",
                        Map.of("path", filePath, "content", "overwritten content")));
                }
                round1Messages.set(request.messages());
                return Flux.just(
                    new ModelStreamStartedEvent("m"),
                    new ModelTextDeltaEvent("accepted, but read it first next time"),
                    new ModelCompletedEvent());
            };

            ToolPermissionPolicy allowAll = (req, ctx) ->
                Mono.just(new ToolPermissionDecision.Allow());

            SpringToolRegistry registry = new SpringToolRegistry(List.of(
                new FileWriteTool(new com.clawcode.agent.tools.file.FileReadStateStore())));
            DefaultToolExecutor executor = new DefaultToolExecutor(
                registry, allowAll, noopAudit, noopMetrics, List.of());

            var orchestrator = new DefaultQueryOrchestrator(
                modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
                noopAudit, noopMetrics, noopSkills);

            var command = new TurnCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "write to file")),
                "m", null, List.of());

            StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                    && r.toolName().equals("file_write")
                    && r.toolCallId().equals("c-write"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                    && r.isError()
                    && r.summary().contains("read the existing file")
                    && r.summary().contains("with file_read first"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                    && d.text().equals("accepted, but read it first next time"))
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent r
                    && r.permissionDenials() == 0)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            assertThat(round1Messages.get()).isNotNull();
            ToolResultMessage deniedMsg = (ToolResultMessage) round1Messages.get().stream()
                .filter(m -> m instanceof ToolResultMessage)
                .map(m -> (ToolResultMessage) m)
                .filter(m -> "file_write".equals(m.toolName()))
                .findFirst().orElse(null);
            assertThat(deniedMsg).isNotNull();
            assertThat(deniedMsg.toolCallId()).isEqualTo("c-write");
            assertThat(deniedMsg.isError()).isTrue();
            assertThat(deniedMsg.content()).contains("read the existing file")
                .contains("with file_read first");

            assertThat(Files.readString(file)).isEqualTo("original content");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void fileEditSuccessAfterReadInToolLoop() throws IOException {
        Path dir = Path.of("target").resolve("edit-orch");
        Files.createDirectories(dir);
        Path file = dir.resolve("edit-me.txt");
        Files.writeString(file, "original content");

        try {
            String filePath = file.toString();
            FileReadStateStore store = new FileReadStateStore();
            AtomicInteger modelCalls = new AtomicInteger();
            ModelClient modelClient = request -> {
                int call = modelCalls.getAndIncrement();
                if (call == 0) {
                    return Flux.just(new ModelToolUseEvent("c-read", "file_read",
                        Map.of("path", filePath)));
                }
                if (call == 1) {
                    return Flux.just(new ModelToolUseEvent("c-edit", "file_edit",
                        Map.of("path", filePath, "old_text", "original", "new_text", "edited")));
                }
                return Flux.just(
                    new ModelStreamStartedEvent("m"),
                    new ModelTextDeltaEvent("edit completed"),
                    new ModelCompletedEvent());
            };

            SpringToolRegistry registry = new SpringToolRegistry(List.of(
                new FileReadTool(store),
                new FileEditTool(store)));
            DefaultToolExecutor executor = new DefaultToolExecutor(
                registry, (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow()),
                noopAudit, noopMetrics, List.of());

            var orchestrator = new DefaultQueryOrchestrator(
                modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
                noopAudit, noopMetrics, noopSkills);

            var command = new TurnCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "edit the file")),
                "m", null, List.of());

            StepVerifier.create(orchestrator.runTurn(command))
                // round 1: file_read
                .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                    && r.toolName().equals("file_read")
                    && r.toolCallId().equals("c-read"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                    && !r.isError()
                    && r.summary().contains("original content"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
                // round 2: file_edit
                .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                    && r.toolName().equals("file_edit")
                    && r.toolCallId().equals("c-edit"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                    && !r.isError()
                    && r.summary().contains("Edited"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
                // round 3: final answer
                .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                    && d.text().equals("edit completed"))
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            assertThat(Files.readString(file))
                .as("file must be edited on disk after successful file_edit")
                .isEqualTo("edited content");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void deniedEditWithoutPriorReadInTranscriptProducesToolResultError() throws IOException {
        Path dir = Path.of("target").resolve("edit-no-read-orch");
        Files.createDirectories(dir);
        Path file = dir.resolve("existing.txt");
        Files.writeString(file, "original content");

        try {
            String filePath = file.toString();
            AtomicReference<List<com.clawcode.agent.shared.message.Message>> round1Messages =
                new AtomicReference<>();
            AtomicInteger modelCalls = new AtomicInteger();
            ModelClient modelClient = request -> {
                int call = modelCalls.getAndIncrement();
                if (call == 0) {
                    return Flux.just(new ModelToolUseEvent("c-edit", "file_edit",
                        Map.of("path", filePath, "old_text", "original", "new_text", "edited")));
                }
                round1Messages.set(request.messages());
                return Flux.just(
                    new ModelStreamStartedEvent("m"),
                    new ModelTextDeltaEvent("need to read first"),
                    new ModelCompletedEvent());
            };

            ToolPermissionPolicy allowAll = (req, ctx) ->
                Mono.just(new ToolPermissionDecision.Allow());

            SpringToolRegistry registry = new SpringToolRegistry(List.of(
                new FileEditTool(new FileReadStateStore())));
            DefaultToolExecutor executor = new DefaultToolExecutor(
                registry, allowAll, noopAudit, noopMetrics, List.of());

            var orchestrator = new DefaultQueryOrchestrator(
                modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
                noopAudit, noopMetrics, noopSkills);

            var command = new TurnCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "edit file")),
                "m", null, List.of());

            StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                    && r.toolName().equals("file_edit")
                    && r.toolCallId().equals("c-edit"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                    && r.isError()
                    && r.summary().contains("read the existing file")
                    && r.summary().contains("with file_read first"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                    && d.text().equals("need to read first"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            assertThat(round1Messages.get()).isNotNull();
            ToolResultMessage deniedMsg = (ToolResultMessage) round1Messages.get().stream()
                .filter(m -> m instanceof ToolResultMessage)
                .map(m -> (ToolResultMessage) m)
                .filter(m -> "file_edit".equals(m.toolName()))
                .findFirst().orElse(null);
            assertThat(deniedMsg).isNotNull();
            assertThat(deniedMsg.toolCallId()).isEqualTo("c-edit");
            assertThat(deniedMsg.isError()).isTrue();
            assertThat(deniedMsg.content()).contains("read the existing file")
                .contains("with file_read first");

            assertThat(Files.readString(file)).isEqualTo("original content");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void unknownToolFollowUpRoundRecoversWithExplanation() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(new ModelToolUseEvent("c-unknown", "not_a_tool",
                    Map.of("input", "anything")));
            }
            ToolResultMessage msg = (ToolResultMessage) request.messages().stream()
                .filter(m -> m instanceof ToolResultMessage)
                .filter(m -> ((ToolResultMessage) m).toolName().equals("not_a_tool"))
                .findFirst().orElse(null);
            if (msg == null) {
                return Flux.just(new ModelErrorEvent("expected ToolResultMessage"));
            }
            if (!"c-unknown".equals(msg.toolCallId())) {
                return Flux.just(new ModelErrorEvent(
                    "expected toolCallId 'c-unknown', got '" + msg.toolCallId() + "'"));
            }
            if (!msg.isError()) {
                return Flux.just(new ModelErrorEvent("expected isError=true"));
            }
            if (!msg.content().contains("Unknown tool")) {
                return Flux.just(new ModelErrorEvent("expected 'Unknown tool' in content"));
            }
            if (!msg.content().contains("Do not retry")) {
                return Flux.just(new ModelErrorEvent("expected 'Do not retry' in content"));
            }
            if (!msg.content().contains("use an advertised tool")) {
                return Flux.just(new ModelErrorEvent(
                    "expected 'use an advertised tool' in content"));
            }
            return Flux.just(
                new ModelStreamStartedEvent("m"),
                new ModelTextDeltaEvent(
                    "I cannot use that tool. Let me suggest an alternative approach."),
                new ModelCompletedEvent());
        };

        DefaultToolExecutor executor = new DefaultToolExecutor(
            noopRegistry,
            (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow()),
            noopAudit, noopMetrics, List.of());

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "use the tool")),
            "m", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("not_a_tool")
                && r.toolCallId().equals("c-unknown"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && r.isError()
                && r.summary().contains("Unknown tool")
                && r.summary().contains("Do not retry"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                && d.text().contains("I cannot use that tool"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent r
                && r.permissionDenials() == 1)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();

        assertThat(modelCalls.get()).isEqualTo(2);
    }

    @Test
    void mcpReadResourceToolLoopEndToEnd() {
        var serverDef = new McpProperties.McpServerDefinition(true, "http://localhost", "");
        McpService mcpService = new McpService(
            new McpProperties(true, Map.of("my-server", serverDef)),
            new McpClient() {
                @Override
                public Flux<McpResource> listResources(String serverName) {
                    return Flux.empty();
                }
                @Override
                public Mono<McpResourceContent> readResource(String serverName, java.net.URI uri) {
                    return Mono.just(new McpResourceContent(
                        uri, "text/plain", "secret-data"));
                }
            }
        );

        McpReadResourceTool mcpTool = new McpReadResourceTool(mcpService);
        SpringToolRegistry registry = new SpringToolRegistry(List.of(mcpTool));
        DefaultToolExecutor executor = new DefaultToolExecutor(
            registry, (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow()), noopAudit, noopMetrics, List.of());

        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(new ModelToolUseEvent("call-mcp", "mcp_read_resource",
                    Map.of("server", "my-server", "uri", "file:///data.txt")));
            }
            return Flux.just(
                new ModelStreamStartedEvent("glm-5.1"),
                new ModelTextDeltaEvent("Here is the resource content"),
                new ModelCompletedEvent());
        };

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit), noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "read the resource")),
            "glm-5.1", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("mcp_read_resource")
                && r.toolCallId().equals("call-mcp"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && !r.isError()
                && r.summary().contains("secret-data"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                && d.text().equals("Here is the resource content"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();
    }

    @Test
    void webFetchToolLoopEndToEnd() throws IOException {
        MockWebServer httpServer = new MockWebServer();
        httpServer.start();
        httpServer.enqueue(new MockResponse()
            .setBody("<html><body><p>Live web content from e2e</p></body></html>")
            .setHeader("Content-Type", "text/html"));

        try {
            String pageUrl = httpServer.url("/article").toString();

            WebToolsProperties props = new WebToolsProperties(
                true, true, true, null, null, 5_000, 1_048_576, 50_000,
                List.of("http", "https"), List.of());
            WebUrlGuard permissiveGuard = new WebUrlGuard(props) {
                @Override
                public URI validateAndNormalize(String raw) {
                    return URI.create(raw).normalize();
                }
            };
            WebFetchTool webFetch = new WebFetchTool(permissiveGuard, props);

            SpringToolRegistry registry = new SpringToolRegistry(List.of(webFetch));
            DefaultToolExecutor executor = new DefaultToolExecutor(
                registry, (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow()),
                noopAudit, noopMetrics, List.of());

            AtomicInteger modelCalls = new AtomicInteger();
            ModelClient modelClient = request -> {
                int call = modelCalls.getAndIncrement();
                if (call == 0) {
                    return Flux.just(new ModelToolUseEvent("call-fetch", "web_fetch",
                        Map.of("url", pageUrl)));
                }
                return Flux.just(
                    new ModelStreamStartedEvent("glm-5.1"),
                    new ModelTextDeltaEvent("Here is the page summary"),
                    new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit), noopAudit, noopMetrics, noopSkills);

            var command = new TurnCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "fetch the page")),
                "glm-5.1", null, List.of());

            StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                    && r.toolName().equals("web_fetch")
                    && r.toolCallId().equals("call-fetch"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                    && !r.isError()
                    && r.summary().contains("Live web content from e2e"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                    && d.text().equals("Here is the page summary"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();
        } finally {
            httpServer.shutdown();
        }
    }

    @Test
    void skillPlusPluginToolEndToEnd() throws IOException {
        // --- filesystem skill fixture ---
        Path skillRoot = Files.createTempDirectory("skills-e2e");
        Path skillDir = skillRoot.resolve("translator");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
            "You are a translation assistant. Always respond in the target language.");

        // --- plugin tool backend (MockWebServer) ---
        MockWebServer pluginServer = new MockWebServer();
        pluginServer.start();
        pluginServer.enqueue(new MockResponse()
            .setBody("{\"translation\":\"bonjour\"}")
            .setHeader("Content-Type", "application/json"));

        try {
            String pluginUrl = pluginServer.url("/translate").toString();

            // --- skill subsystem ---
            FileSystemSkillRegistry skillRegistry = new FileSystemSkillRegistry(
                new SkillsProperties(true, skillRoot.toString()));
            SkillContextService skillService = new SkillContextService(skillRegistry);

            // --- plugin tool in registry ---
            var pluginDesc = new PluginDescriptor("translator-plugin", "Translator", "1.0", null,
                List.of(new PluginToolDescriptor("translate_api", "http",
                    Map.of("url", pluginUrl, "method", "POST", "timeout", 5000))));

            PluginRegistry pluginRegistry = new PluginRegistry() {
                @Override public Flux<PluginDescriptor> list() { return Flux.just(pluginDesc); }
                @Override public Mono<PluginDescriptor> resolve(String id) { return Mono.just(pluginDesc); }
            };

            DefaultPluginToolFactory pluginFactory = new DefaultPluginToolFactory();
            SpringToolRegistry registry = new SpringToolRegistry(
                List.of(), pluginFactory, pluginRegistry);
            DefaultToolExecutor executor = new DefaultToolExecutor(
                registry, (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow()),
                noopAudit, noopMetrics, List.of());

            // --- model client ---
            AtomicInteger modelCalls = new AtomicInteger();
            AtomicInteger skillVerified = new AtomicInteger();
            ModelClient modelClient = request -> {
                int call = modelCalls.getAndIncrement();
                if (call == 0) {
                    // verify skill enriched the system prompt
                    String prompt = request.systemPrompt();
                    if (prompt != null
                        && prompt.contains("translation assistant")
                        && prompt.contains("--- Skill: translator ---")) {
                        skillVerified.incrementAndGet();
                    }
                    return Flux.just(new ModelToolUseEvent("call-translate", "translate_api",
                        Map.of("text", "hello")));
                }
                return Flux.just(
                    new ModelStreamStartedEvent("glm-5.1"),
                    new ModelTextDeltaEvent("bonjour"),
                    new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
                noopAudit, noopMetrics, skillService);

            var command = new TurnCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "translate hello")),
                "glm-5.1", "base system", List.of("translator"));

            StepVerifier.create(orchestrator.runTurn(command))
                // round 1: model calls plugin tool
                .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                    && r.toolName().equals("translate_api")
                    && r.toolCallId().equals("call-translate"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                    && !r.isError()
                    && r.summary().contains("bonjour"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
                // round 2: model uses tool result for final answer
                .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                    && d.text().equals("bonjour"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            assertThat(skillVerified.get()).isEqualTo(1);
        } finally {
            pluginServer.shutdown();
            deleteRecursively(skillRoot);
        }
    }

    @Test
    void taskToolLoopEndToEnd() {
        InMemoryTaskExecutor taskExecutor = new InMemoryTaskExecutor(
            input -> Mono.just("computed: " + input));
        TaskService taskService = new TaskService(taskExecutor);

        SpringToolRegistry registry = new SpringToolRegistry(List.of(
            new TaskSubmitTool(taskService),
            new TaskStatusTool(taskService),
            new TaskResultTool(taskService)));
        DefaultToolExecutor executor = new DefaultToolExecutor(
            registry, (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow()),
            noopAudit, noopMetrics, List.of());

        AtomicReference<String> submittedTaskId = new AtomicReference<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(new ModelToolUseEvent("call-submit", "task_submit",
                    Map.of("session_id", "s-e2e", "input", "analyze data")));
            }
            // Extract task_id from ToolResultMessage in conversation history
            String taskId = request.messages().stream()
                .filter(m -> m instanceof com.clawcode.agent.shared.message.ToolResultMessage)
                .map(m -> (com.clawcode.agent.shared.message.ToolResultMessage) m)
                .filter(m -> "task_submit".equals(m.toolName()))
                .map(m -> extractTaskId(m.content()))
                .filter(id -> !id.isEmpty())
                .findFirst()
                .orElse(null);
            if (taskId != null) {
                submittedTaskId.set(taskId);
            }
            if (call == 1) {
                return Flux.just(new ModelToolUseEvent("call-status", "task_status",
                    Map.of("task_id", taskId)));
            }
            if (call == 2) {
                return Flux.just(new ModelToolUseEvent("call-result", "task_result",
                    Map.of("task_id", taskId)));
            }
            return Flux.just(
                new ModelStreamStartedEvent("glm-5.1"),
                new ModelTextDeltaEvent("Task completed successfully"),
                new ModelCompletedEvent());
        };

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "run the task")),
            "glm-5.1", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            // round 1: task_submit
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("task_submit")
                && r.toolCallId().equals("call-submit"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && !r.isError()
                && r.summary().contains("task_id"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            // round 2: task_status
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("task_status")
                && r.toolCallId().equals("call-status"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && !r.isError()
                && r.summary().contains("COMPLETED"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            // round 3: task_result
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("task_result")
                && r.toolCallId().equals("call-result"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && !r.isError()
                && r.summary().contains("computed: analyze data"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            // round 4: model produces final answer
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                && d.text().equals("Task completed successfully"))
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();

        assertThat(submittedTaskId.get()).isNotBlank();
    }

    @Test
    void skillContextDoesNotLeakBetweenIndependentTurns() throws IOException {
        // --- filesystem skill fixture ---
        Path skillRoot = Files.createTempDirectory("skills-isolation");
        Path skillDir = skillRoot.resolve("translator");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
            "You are a translation assistant.");

        try {
            FileSystemSkillRegistry skillRegistry = new FileSystemSkillRegistry(
                new SkillsProperties(true, skillRoot.toString()));
            SkillContextService skillService = new SkillContextService(skillRegistry);

            ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
                ToolResult.success(req.toolCallId(), req.toolName(), "ok"));

            // Capture the systemPrompt from each model invocation
            AtomicReference<String> firstPrompt = new AtomicReference<>();
            AtomicReference<String> secondPrompt = new AtomicReference<>();
            AtomicInteger modelCalls = new AtomicInteger();

            ModelClient modelClient = request -> {
                int call = modelCalls.getAndIncrement();
                if (call == 0) {
                    firstPrompt.set(request.systemPrompt());
                    return Flux.just(new ModelCompletedEvent());
                }
                secondPrompt.set(request.systemPrompt());
                return Flux.just(new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
                noopAudit, noopMetrics, skillService);

            // Turn 1: with skill
            var command1 = new TurnCommand(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "translate")),
                "glm-5.1", "base prompt", List.of("translator"));

            StepVerifier.create(orchestrator.runTurn(command1))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            assertThat(firstPrompt.get())
                .contains("translator")
                .contains("--- Skill: translator ---")
                .contains("## Active Skills");

            // Turn 2: no skill
            var command2 = new TurnCommand(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
                "glm-5.1", "base prompt", List.of());

            StepVerifier.create(orchestrator.runTurn(command2))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            assertThat(secondPrompt.get())
                .doesNotContain("translator")
                .doesNotContain("## Active Skills");
        } finally {
            deleteRecursively(skillRoot);
        }
    }

    @Test
    void sameSkillInputProducesDeterministicSystemPrompt() throws IOException {
        Path skillRoot = Files.createTempDirectory("skills-determinism");
        Path translatorDir = skillRoot.resolve("translator");
        Path summarizerDir = skillRoot.resolve("summarizer");
        Files.createDirectories(translatorDir);
        Files.createDirectories(summarizerDir);
        Files.writeString(translatorDir.resolve("SKILL.md"),
            "You are a translation assistant. Always respond in the target language.");
        Files.writeString(summarizerDir.resolve("SKILL.md"),
            "You are a summarization assistant. Produce concise summaries.");

        try {
            FileSystemSkillRegistry skillRegistry = new FileSystemSkillRegistry(
                new SkillsProperties(true, skillRoot.toString()));
            SkillContextService skillService = new SkillContextService(skillRegistry);

            ToolExecutor toolExecutor = (req, ctx) -> Mono.just(
                ToolResult.success(req.toolCallId(), req.toolName(), "ok"));

            AtomicReference<String> firstPrompt = new AtomicReference<>();
            AtomicReference<String> secondPrompt = new AtomicReference<>();
            AtomicInteger modelCalls = new AtomicInteger();

            ModelClient modelClient = request -> {
                int call = modelCalls.getAndIncrement();
                if (call == 0) {
                    firstPrompt.set(request.systemPrompt());
                } else {
                    secondPrompt.set(request.systemPrompt());
                }
                return Flux.just(new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                modelClient, toolExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
                noopAudit, noopMetrics, skillService);

            List<String> skillIds = List.of("translator", "summarizer");
            String basePrompt = "You are a helpful assistant.";

            var command1 = new TurnCommand(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "first")),
                "glm-5.1", basePrompt, skillIds);

            var command2 = new TurnCommand(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "second")),
                "glm-5.1", basePrompt, skillIds);

            StepVerifier.create(orchestrator.runTurn(command1))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            StepVerifier.create(orchestrator.runTurn(command2))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            assertThat(stripTimestamp(firstPrompt.get())).isEqualTo(stripTimestamp(secondPrompt.get()));
        } finally {
            deleteRecursively(skillRoot);
        }
    }

    @Test
    void multiplePluginToolsInOneFlow() throws IOException {
        MockWebServer pluginServer = new MockWebServer();
        pluginServer.start();
        pluginServer.enqueue(new MockResponse()
            .setBody("{\"temp\":22,\"city\":\"Berlin\"}")
            .setHeader("Content-Type", "application/json"));
        pluginServer.enqueue(new MockResponse()
            .setBody("{\"rate\":1.08,\"from\":\"EUR\",\"to\":\"USD\"}")
            .setHeader("Content-Type", "application/json"));

        try {
            String baseUrl = pluginServer.url("").toString();

            var pluginDesc = new PluginDescriptor("multi-plugin", "Multi", "1.0", null,
                List.of(
                    new PluginToolDescriptor("weather_lookup", "http",
                        Map.of("url", baseUrl + "weather", "method", "GET", "timeout", 5000)),
                    new PluginToolDescriptor("currency_lookup", "http",
                        Map.of("url", baseUrl + "currency", "method", "GET", "timeout", 5000))
                ));

            PluginRegistry pluginRegistry = new PluginRegistry() {
                @Override public Flux<PluginDescriptor> list() { return Flux.just(pluginDesc); }
                @Override public Mono<PluginDescriptor> resolve(String id) { return Mono.just(pluginDesc); }
            };

            DefaultPluginToolFactory pluginFactory = new DefaultPluginToolFactory();
            SpringToolRegistry registry = new SpringToolRegistry(List.of(), pluginFactory, pluginRegistry);
            DefaultToolExecutor executor = new DefaultToolExecutor(
                registry, (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow()),
                noopAudit, noopMetrics, List.of());

            AtomicInteger modelCalls = new AtomicInteger();
            ModelClient modelClient = request -> {
                int call = modelCalls.getAndIncrement();
                if (call == 0) {
                    return Flux.just(new ModelToolUseEvent("call-weather", "weather_lookup",
                        Map.of("city", "Berlin")));
                }
                if (call == 1) {
                    return Flux.just(new ModelToolUseEvent("call-currency", "currency_lookup",
                        Map.of("from", "EUR", "to", "USD")));
                }
                return Flux.just(
                    new ModelStreamStartedEvent("glm-5.1"),
                    new ModelTextDeltaEvent("Berlin is 22C, EUR→USD = 1.08"),
                    new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
                noopAudit, noopMetrics, noopSkills);

            var command = new TurnCommand(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "weather and currency")),
                "glm-5.1", null, List.of());

            StepVerifier.create(orchestrator.runTurn(command))
                // round 1: weather_lookup
                .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                    && r.toolName().equals("weather_lookup")
                    && r.toolCallId().equals("call-weather"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                    && !r.isError()
                    && r.summary().contains("Berlin"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
                // round 2: currency_lookup
                .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                    && r.toolName().equals("currency_lookup")
                    && r.toolCallId().equals("call-currency"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                    && !r.isError()
                    && r.summary().contains("1.08"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
                // round 3: final answer
                .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                    && d.text().equals("Berlin is 22C, EUR→USD = 1.08"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();
        } finally {
            pluginServer.shutdown();
        }
    }

    @Test
    void taskPollingMultipleStatusesThenResult() {
        InMemoryTaskExecutor taskExecutor = new InMemoryTaskExecutor(
            input -> Mono.just("computed: " + input));
        TaskService taskService = new TaskService(taskExecutor);

        SpringToolRegistry registry = new SpringToolRegistry(List.of(
            new TaskSubmitTool(taskService),
            new TaskStatusTool(taskService),
            new TaskResultTool(taskService)));
        DefaultToolExecutor executor = new DefaultToolExecutor(
            registry, (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow()),
            noopAudit, noopMetrics, List.of());

        AtomicReference<String> submittedTaskId = new AtomicReference<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(new ModelToolUseEvent("call-submit", "task_submit",
                    Map.of("session_id", "s-poll", "input", "heavy job")));
            }
            // extract task_id from tool result history
            String taskId = request.messages().stream()
                .filter(m -> m instanceof com.clawcode.agent.shared.message.ToolResultMessage)
                .map(m -> (com.clawcode.agent.shared.message.ToolResultMessage) m)
                .filter(m -> "task_submit".equals(m.toolName()))
                .map(m -> extractTaskId(m.content()))
                .filter(id -> !id.isEmpty())
                .findFirst()
                .orElse(null);
            if (taskId != null) {
                submittedTaskId.set(taskId);
            }
            if (call == 1) {
                // first poll — status
                return Flux.just(new ModelToolUseEvent("call-status-1", "task_status",
                    Map.of("task_id", taskId)));
            }
            if (call == 2) {
                // second poll — status again
                return Flux.just(new ModelToolUseEvent("call-status-2", "task_status",
                    Map.of("task_id", taskId)));
            }
            if (call == 3) {
                // fetch result
                return Flux.just(new ModelToolUseEvent("call-result", "task_result",
                    Map.of("task_id", taskId)));
            }
            // final answer
            return Flux.just(
                new ModelStreamStartedEvent("glm-5.1"),
                new ModelTextDeltaEvent("Job done: computed: heavy job"),
                new ModelCompletedEvent());
        };

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "run and poll")),
            "glm-5.1", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            // round 1: task_submit
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("task_submit")
                && r.toolCallId().equals("call-submit"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && !r.isError()
                && r.summary().contains("task_id"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            // round 2: first task_status poll
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("task_status")
                && r.toolCallId().equals("call-status-1"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && !r.isError()
                && r.summary().contains("COMPLETED"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            // round 3: second task_status poll
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("task_status")
                && r.toolCallId().equals("call-status-2"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && !r.isError()
                && r.summary().contains("COMPLETED"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            // round 4: task_result
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("task_result")
                && r.toolCallId().equals("call-result"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && !r.isError()
                && r.summary().contains("computed: heavy job"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            // round 5: final model output
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                && d.text().equals("Job done: computed: heavy job"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();

        assertThat(submittedTaskId.get()).isNotBlank();
    }

    @Test
    void failedTaskResultPropagatesAsToolResultError() {
        InMemoryTaskExecutor taskExecutor = new InMemoryTaskExecutor(
            input -> Mono.error(new RuntimeException("task failed: " + input)));
        TaskService taskService = new TaskService(taskExecutor);

        SpringToolRegistry registry = new SpringToolRegistry(List.of(
            new TaskSubmitTool(taskService),
            new TaskStatusTool(taskService),
            new TaskResultTool(taskService)));
        DefaultToolExecutor executor = new DefaultToolExecutor(
            registry, (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow()),
            noopAudit, noopMetrics, List.of());

        AtomicReference<String> submittedTaskId = new AtomicReference<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(new ModelToolUseEvent("call-submit", "task_submit",
                    Map.of("session_id", "s-fail", "input", "bad job")));
            }
            String taskId = request.messages().stream()
                .filter(m -> m instanceof com.clawcode.agent.shared.message.ToolResultMessage)
                .map(m -> (com.clawcode.agent.shared.message.ToolResultMessage) m)
                .filter(m -> "task_submit".equals(m.toolName()))
                .map(m -> extractTaskId(m.content()))
                .filter(id -> !id.isEmpty())
                .findFirst()
                .orElse(null);
            if (taskId != null) {
                submittedTaskId.set(taskId);
            }
            if (call == 1) {
                return Flux.just(new ModelToolUseEvent("call-status", "task_status",
                    Map.of("task_id", taskId)));
            }
            if (call == 2) {
                return Flux.just(new ModelToolUseEvent("call-result", "task_result",
                    Map.of("task_id", taskId)));
            }
            return Flux.just(
                new ModelStreamStartedEvent("glm-5.1"),
                new ModelTextDeltaEvent("Task failed as expected"),
                new ModelCompletedEvent());
        };

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "run failing task")),
            "glm-5.1", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            // round 1: task_submit
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("task_submit")
                && r.toolCallId().equals("call-submit"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && !r.isError()
                && r.summary().contains("task_id"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            // round 2: task_status — should report FAILED
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("task_status")
                && r.toolCallId().equals("call-status"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && !r.isError()
                && r.summary().contains("FAILED")
                && r.summary().contains("task failed"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            // round 3: task_result — error field present
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("task_result")
                && r.toolCallId().equals("call-result"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && !r.isError()
                && r.summary().contains("FAILED")
                && r.summary().contains("task failed"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            // round 4: model produces final answer using error info
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                && d.text().equals("Task failed as expected"))
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();

        assertThat(submittedTaskId.get()).isNotBlank();
    }

    @Test
    void hooksRunInOrderThroughOrchestrator() {
        List<String> order = new ArrayList<>();

        ToolExecutionHook hookA = new ToolExecutionHook() {
            @Override public Mono<Void> beforeExecute(ToolUseRequest req, ToolExecutionContext ctx) {
                order.add("A.before");
                return Mono.empty();
            }
            @Override public Mono<Void> afterExecute(ToolUseRequest req, ToolExecutionContext ctx, ToolResult result) {
                order.add("A.after");
                return Mono.empty();
            }
        };
        ToolExecutionHook hookB = new ToolExecutionHook() {
            @Override public Mono<Void> beforeExecute(ToolUseRequest req, ToolExecutionContext ctx) {
                order.add("B.before");
                return Mono.empty();
            }
            @Override public Mono<Void> afterExecute(ToolUseRequest req, ToolExecutionContext ctx, ToolResult result) {
                order.add("B.after");
                return Mono.empty();
            }
        };

        Tool echo = new Tool() {
            @Override public String name() { return "echo"; }
            @Override public ToolDefinition definition() { return new ToolDefinition(name(), "test tool", Map.of()); }
            @Override public Mono<Object> execute(Object input, Object context) {
                return Mono.just("result:" + input);
            }
        };
        ToolRegistry registry = new ToolRegistry() {
            @Override public Optional<Tool> findByName(String name) {
                return "echo".equals(name) ? Optional.of(echo) : Optional.empty();
            }
            @Override public java.util.Set<String> listNames() { return java.util.Set.of("echo"); }
        };
        DefaultToolExecutor executor = new DefaultToolExecutor(
            registry, (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow()),
            noopAudit, noopMetrics, List.of(hookA, hookB));

        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(new ModelToolUseEvent("call-1", "echo", "hello"));
            }
            return Flux.just(
                new ModelStreamStartedEvent("glm-5.1"),
                new ModelTextDeltaEvent("final answer"),
                new ModelCompletedEvent());
        };

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, executor, registry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "go")),
            "glm-5.1", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("echo")
                && r.toolCallId().equals("call-1"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && !r.isError())
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                && d.text().equals("final answer"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();

        assertThat(order).containsExactly("A.before", "B.before", "A.after", "B.after");
    }

    @Test
    void beforeHookStopsToolExecutionThroughOrchestrator() {
        AtomicInteger executeCount = new AtomicInteger();

        Tool echo = new Tool() {
            @Override public String name() { return "echo"; }
            @Override public ToolDefinition definition() { return new ToolDefinition(name(), "test tool", Map.of()); }
            @Override public Mono<Object> execute(Object input, Object context) {
                return Mono.defer(() -> {
                    executeCount.incrementAndGet();
                    return Mono.just("should not run");
                });
            }
        };
        ToolRegistry registry = new ToolRegistry() {
            @Override public Optional<Tool> findByName(String name) {
                return "echo".equals(name) ? Optional.of(echo) : Optional.empty();
            }
            @Override public java.util.Set<String> listNames() { return java.util.Set.of("echo"); }
        };

        ToolExecutionHook blockingHook = new ToolExecutionHook() {
            @Override public Mono<Void> beforeExecute(ToolUseRequest req, ToolExecutionContext ctx) {
                return Mono.error(new RuntimeException("blocked by hook"));
            }
            @Override public Mono<Void> afterExecute(ToolUseRequest req, ToolExecutionContext ctx, ToolResult result) {
                return Mono.empty();
            }
        };

        DefaultToolExecutor executor = new DefaultToolExecutor(
            registry, (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow()),
            noopAudit, noopMetrics, List.of(blockingHook));

        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(new ModelToolUseEvent("call-1", "echo", "hello"));
            }
            return Flux.just(
                new ModelStreamStartedEvent("glm-5.1"),
                new ModelTextDeltaEvent("recovered after hook block"),
                new ModelCompletedEvent());
        };

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, executor, registry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "go")),
            "glm-5.1", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("echo"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && r.isError()
                && r.summary().contains("blocked by hook"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                && d.text().equals("recovered after hook block"))
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();

        assertThat(executeCount.get()).isZero();
    }

    @Test
    void afterHookFailureBecomesToolResultErrorThroughOrchestrator() {
        Tool echo = new Tool() {
            @Override public String name() { return "echo"; }
            @Override public ToolDefinition definition() { return new ToolDefinition(name(), "test tool", Map.of()); }
            @Override public Mono<Object> execute(Object input, Object context) {
                return Mono.just("original output");
            }
        };
        ToolRegistry registry = new ToolRegistry() {
            @Override public Optional<Tool> findByName(String name) {
                return "echo".equals(name) ? Optional.of(echo) : Optional.empty();
            }
            @Override public java.util.Set<String> listNames() { return java.util.Set.of("echo"); }
        };

        ToolExecutionHook failingAfterHook = new ToolExecutionHook() {
            @Override public Mono<Void> beforeExecute(ToolUseRequest req, ToolExecutionContext ctx) {
                return Mono.empty();
            }
            @Override public Mono<Void> afterExecute(ToolUseRequest req, ToolExecutionContext ctx, ToolResult result) {
                return Mono.error(new RuntimeException("after failed"));
            }
        };

        DefaultToolExecutor executor = new DefaultToolExecutor(
            registry, (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow()),
            noopAudit, noopMetrics, List.of(failingAfterHook));

        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(new ModelToolUseEvent("call-1", "echo", "hello"));
            }
            return Flux.just(
                new ModelStreamStartedEvent("glm-5.1"),
                new ModelTextDeltaEvent("handled after-hook failure"),
                new ModelCompletedEvent());
        };

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, executor, registry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "go")),
            "glm-5.1", null, List.of());

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("echo"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && r.isError()
                && r.summary().contains("after failed"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                && d.text().equals("handled after-hook failure"))
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();
    }

    @Test
    void mixedSkillPluginAndTaskFlowEndToEnd() throws IOException {
        // --- skill fixture ---
        Path skillRoot = Files.createTempDirectory("skills-mixed");
        Path skillDir = skillRoot.resolve("translator");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
            "You are a translation assistant.");

        // --- plugin tool backend ---
        MockWebServer pluginServer = new MockWebServer();
        pluginServer.start();
        pluginServer.enqueue(new MockResponse()
            .setBody("{\"translation\":\"hola\"}")
            .setHeader("Content-Type", "application/json"));

        try {
            String pluginUrl = pluginServer.url("/translate").toString();

            // --- skill subsystem ---
            FileSystemSkillRegistry skillRegistry = new FileSystemSkillRegistry(
                new SkillsProperties(true, skillRoot.toString()));
            SkillContextService skillService = new SkillContextService(skillRegistry);

            // --- plugin tool in registry ---
            var pluginDesc = new PluginDescriptor("t-plugin", "Translator", "1.0", null,
                List.of(new PluginToolDescriptor("translate_api", "http",
                    Map.of("url", pluginUrl, "method", "POST", "timeout", 5000))));
            PluginRegistry pluginRegistry = new PluginRegistry() {
                @Override public Flux<PluginDescriptor> list() { return Flux.just(pluginDesc); }
                @Override public Mono<PluginDescriptor> resolve(String id) { return Mono.just(pluginDesc); }
            };
            DefaultPluginToolFactory pluginFactory = new DefaultPluginToolFactory();

            // --- task tools ---
            InMemoryTaskExecutor taskExecutor = new InMemoryTaskExecutor(
                input -> Mono.just("translated: " + input));
            TaskService taskService = new TaskService(taskExecutor);

            // --- combined registry ---
            SpringToolRegistry registry = new SpringToolRegistry(
                List.of(new TaskSubmitTool(taskService),
                    new TaskStatusTool(taskService),
                    new TaskResultTool(taskService)),
                pluginFactory, pluginRegistry);
            DefaultToolExecutor executor = new DefaultToolExecutor(
                registry, (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow()),
                noopAudit, noopMetrics, List.of());

            // --- model client ---
            AtomicInteger skillVerified = new AtomicInteger();
            AtomicReference<String> submittedTaskId = new AtomicReference<>();
            AtomicInteger modelCalls = new AtomicInteger();
            ModelClient modelClient = request -> {
                int call = modelCalls.getAndIncrement();
                if (call == 0) {
                    // verify skill context in system prompt
                    String prompt = request.systemPrompt();
                    if (prompt != null
                        && prompt.contains("--- Skill: translator ---")
                        && prompt.contains("## Active Skills")) {
                        skillVerified.incrementAndGet();
                    }
                    return Flux.just(new ModelToolUseEvent("call-translate", "translate_api",
                        Map.of("text", "hello")));
                }
                // extract task_id from history (available after task_submit round)
                request.messages().stream()
                    .filter(m -> m instanceof com.clawcode.agent.shared.message.ToolResultMessage)
                    .map(m -> (com.clawcode.agent.shared.message.ToolResultMessage) m)
                    .filter(m -> "task_submit".equals(m.toolName()))
                    .map(m -> extractTaskId(m.content()))
                    .filter(id -> !id.isEmpty())
                    .findFirst()
                    .ifPresent(submittedTaskId::set);
                if (call == 1) {
                    return Flux.just(new ModelToolUseEvent("call-submit", "task_submit",
                        Map.of("session_id", "s-mixed", "input", "batch translate")));
                }
                if (call == 2) {
                    return Flux.just(new ModelToolUseEvent("call-result", "task_result",
                        Map.of("task_id", submittedTaskId.get())));
                }
                return Flux.just(
                    new ModelStreamStartedEvent("glm-5.1"),
                    new ModelTextDeltaEvent("All done: hola + translated: batch translate"),
                    new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
                noopAudit, noopMetrics, skillService);

            var command = new TurnCommand(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "translate all")),
                "glm-5.1", "base system", List.of("translator"));

            StepVerifier.create(orchestrator.runTurn(command))
                // round 1: plugin tool (skill context already injected)
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                    && r.toolName().equals("translate_api")
                    && r.toolCallId().equals("call-translate"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                    && !r.isError()
                    && r.summary().contains("hola"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
                // round 2: task_submit
                .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                    && r.toolName().equals("task_submit")
                    && r.toolCallId().equals("call-submit"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                    && !r.isError()
                    && r.summary().contains("task_id"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
                // round 3: task_result
                .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                    && r.toolName().equals("task_result")
                    && r.toolCallId().equals("call-result"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                    && !r.isError()
                    && r.summary().contains("translated: batch translate"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
                // round 4: final model output
                .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent d
                    && d.text().equals("All done: hola + translated: batch translate"))
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            assertThat(skillVerified.get()).isEqualTo(1);
            assertThat(submittedTaskId.get()).isNotBlank();
        } finally {
            pluginServer.shutdown();
            deleteRecursively(skillRoot);
        }
    }

    @Test
    void dynamicRegistryResolvesPerCallWithinSingleTurn() {
        // Mutable registry — tools can be swapped between model rounds
        var tools = new java.util.concurrent.ConcurrentHashMap<String, Tool>();
        tools.put("echo", new Tool() {
            @Override public String name() { return "echo"; }
            @Override public ToolDefinition definition() { return new ToolDefinition(name(), "test tool", Map.of()); }
            @Override public Mono<Object> execute(Object input, Object context) {
                return Mono.just("v1:" + input);
            }
        });
        ToolRegistry mutableRegistry = new ToolRegistry() {
            @Override public Optional<Tool> findByName(String name) {
                return Optional.ofNullable(tools.get(name));
            }
            @Override public java.util.Set<String> listNames() { return tools.keySet(); }
        };

        DefaultToolExecutor executor = new DefaultToolExecutor(
            mutableRegistry, (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow()),
            noopAudit, noopMetrics, List.of());

        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(new ModelToolUseEvent("call-1", "echo", "hello"));
            }
            if (call == 1) {
                return Flux.just(new ModelToolUseEvent("call-2", "echo", "world"));
            }
            return Flux.just(
                new ModelStreamStartedEvent("glm-5.1"),
                new ModelCompletedEvent());
        };

        // Swap the tool implementation after the first tool result event fires
        var resultEvents = new java.util.concurrent.atomic.AtomicBoolean(false);
        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "go")),
            "glm-5.1", null, List.of());

        StepVerifier.create(
            orchestrator.runTurn(command)
                .doOnNext(e -> {
                    if (e instanceof QueryToolResultEvent && !resultEvents.getAndSet(true)) {
                        // Mutate registry between round 1 and round 2
                        tools.put("echo", new Tool() {
                            @Override public String name() { return "echo"; }
                            @Override public ToolDefinition definition() { return new ToolDefinition(name(), "test tool", Map.of()); }
                            @Override public Mono<Object> execute(Object input, Object context) {
                                return Mono.just("v2:" + input);
                            }
                        });
                    }
                })
        )
            // round 1: v1 implementation
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("echo"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && !r.isError()
                && r.summary().contains("v1:hello"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            // round 2: v2 implementation — registry resolved dynamically
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolName().equals("echo"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryToolResultEvent r
                && !r.isError()
                && r.summary().contains("v2:world"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            // round 3: final model output
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();
    }

    @Test
    void parallelReadOnlyBatchPreservesOrderWithDelayedTools() {
        String largeMiddleMarker = "PARALLEL-LARGE-MIDDLE";
        String largeSlowResult = "SLOW-HEAD" + "s".repeat(7_000)
            + largeMiddleMarker + "t".repeat(7_000) + "SLOW-TAIL";
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(
                    new ModelStreamStartedEvent("glm-5.1"),
                    new ModelToolUseEvent("slow", "file_read", Map.of("path", "/a")),
                    new ModelToolUseEvent("medium", "file_read", Map.of("path", "/b")),
                    new ModelToolUseEvent("fast", "file_read", Map.of("path", "/c")),
                    new ModelCompletedEvent()
                );
            }
            return Flux.just(
                new ModelTextDeltaEvent("all done"),
                new ModelCompletedEvent()
            );
        };

        // Executor with staggered delays: slow=100ms, medium=50ms, fast=10ms.
        // Concurrent execution is proven deterministically via peakActive > 1.
        var active = new java.util.concurrent.atomic.AtomicInteger(0);
        var peakActive = new java.util.concurrent.atomic.AtomicInteger(0);
        ToolExecutor delayedExecutor = (req, ctx) ->
            Mono.defer(() -> {
                long delay = switch (req.toolCallId()) {
                    case "slow" -> 100L;
                    case "medium" -> 50L;
                    case "fast" -> 10L;
                    default -> 0L;
                };
                int now = active.incrementAndGet();
                peakActive.updateAndGet(p -> Math.max(p, now));
                String output = switch (req.toolCallId()) {
                    case "slow" -> largeSlowResult;
                    case "medium" -> "result:/b";
                    case "fast" -> "result:/c";
                    default -> "result:" + req.input();
                };
                return Mono.delay(java.time.Duration.ofMillis(delay))
                    .thenReturn(ToolResult.success(req.toolCallId(), req.toolName(), output))
                    .doOnSuccess(v -> active.decrementAndGet());
            });

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, delayedExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "go")),
            "glm-5.1", null, List.of());

        java.util.List<QueryToolResultEvent> results = new java.util.ArrayList<>();
        java.util.List<QueryToolUseSummaryEvent> summaries = new java.util.ArrayList<>();
        List<List<Message>> transcriptUpdates = new ArrayList<>();

        StepVerifier.create(orchestrator.runTurn(command)
                .doOnNext(e -> {
                    if (e instanceof QueryTranscriptUpdateEvent u) {
                        transcriptUpdates.add(u.update().messagesToPersist());
                    }
                }))
            // Stream started event
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            // Three requested events in order
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolCallId().equals("slow"))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolCallId().equals("medium"))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolCallId().equals("fast"))
            // Three result events -- must be in original call order, not completion order
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> {
                if (e instanceof QueryToolResultEvent r) {
                    results.add(r);
                    return r.toolCallId().equals("slow");
                }
                return false;
            })
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> {
                if (e instanceof QueryToolResultEvent r) {
                    results.add(r);
                    return r.toolCallId().equals("medium");
                }
                return false;
            })
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> {
                if (e instanceof QueryToolResultEvent r) {
                    results.add(r);
                    return r.toolCallId().equals("fast");
                }
                return false;
            })
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> {
                if (e instanceof QueryToolUseSummaryEvent s) {
                    summaries.add(s);
                    return s.totalToolCalls() == 3
                        && s.compactedResults() == 1
                        && s.errorResults() == 0
                        && s.paths().equals(List.of("/a", "/b", "/c"));
                }
                return false;
            })
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            // Follow-up round
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent
                && ((QueryTextDeltaEvent) e).text().equals("all done"))
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();

        assertThat(results).hasSize(3);
        assertThat(summaries).hasSize(1);
        // Transcript order matches original call order, not completion order
        assertThat(results).extracting(QueryToolResultEvent::toolCallId)
            .containsExactly("slow", "medium", "fast");
        // All three results have the correct tool name
        assertThat(results).allMatch(r -> r.toolName().equals("file_read"));
        assertThat(results.get(0).summary())
            .contains("[tool result compacted]")
            .contains("tool_call_id: slow")
            .contains("path: /a")
            .doesNotContain(largeMiddleMarker);
        assertThat(results.get(1).summary()).isEqualTo("result:/b");
        assertThat(results.get(2).summary()).isEqualTo("result:/c");

        List<ToolResultMessage> toolResultMessages = transcriptUpdates.stream()
            .flatMap(List::stream)
            .filter(ToolResultMessage.class::isInstance)
            .map(ToolResultMessage.class::cast)
            .toList();
        assertThat(toolResultMessages).extracting(ToolResultMessage::toolCallId)
            .containsExactly("slow", "medium", "fast");
        assertThat(toolResultMessages.get(0).content())
            .contains("[tool result compacted]")
            .contains("tool_call_id: slow")
            .contains("path: /a")
            .doesNotContain(largeMiddleMarker);
        assertThat(toolResultMessages.get(1).content()).isEqualTo("result:/b");
        assertThat(toolResultMessages.get(2).content()).isEqualTo("result:/c");
        List<UserMessage> summaryMessages = transcriptUpdates.stream()
            .flatMap(List::stream)
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .filter(m -> m.content().contains("[tool batch summary]"))
            .toList();
        assertThat(summaryMessages).hasSize(1);
        assertThat(summaryMessages.getFirst().content())
            .contains("total_tool_calls: 3")
            .contains("compacted_results: 1")
            .contains("paths: /a, /b, /c")
            .doesNotContain(largeMiddleMarker);
        // Deterministic concurrency proof: at least two tools executed concurrently
        assertThat(peakActive.get()).as("concurrent tool execution detected").isGreaterThan(1);

    }

    @Test
    void serialToolsExecuteStrictlySequentiallyNoOverlap() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(
                    new ModelStreamStartedEvent("glm-5.1"),
                    new ModelToolUseEvent("c1", "file_write", "/a"),
                    new ModelToolUseEvent("c2", "file_edit", "/b"),
                    new ModelToolUseEvent("c3", "powershell", "rm -rf /"),
                    new ModelCompletedEvent()
                );
            }
            return Flux.just(
                new ModelTextDeltaEvent("done"),
                new ModelCompletedEvent()
            );
        };

        // A Semaphore(1) enforces that only one tool executes at a time.
        // If two subscriptions overlap, the second tryAcquire returns false,
        // which fails the test deterministically.
        var gate = new java.util.concurrent.Semaphore(1);
        var orderLog = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

        // tryAcquire/release must be deferred to Mono subscription time
        // (not mapper-call time) because concatMap calls the mapper eagerly.
        ToolExecutor serialExecutor = (req, ctx) ->
            Mono.defer(() -> {
                if (!gate.tryAcquire()) {
                    return Mono.error(new RuntimeException(
                        "CONCURRENT_EXECUTION call=" + req.toolCallId()));
                }
                orderLog.add("start:" + req.toolCallId());
                return Mono.delay(java.time.Duration.ofMillis(30))
                    .thenReturn(ToolResult.success(req.toolCallId(), req.toolName(),
                        "result:" + req.input()))
                    .doOnSuccess(v -> {
                        orderLog.add("end:" + req.toolCallId());
                        gate.release();
                    });
            });

        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, serialExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills);

        var command = new TurnCommand(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "go")),
            "glm-5.1", null, List.of());

        java.util.List<QueryToolResultEvent> results = new java.util.ArrayList<>();
        java.util.List<QueryToolUseSummaryEvent> summaries = new java.util.ArrayList<>();

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolCallId().equals("c1"))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolCallId().equals("c2"))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolCallId().equals("c3"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> {
                if (e instanceof QueryToolResultEvent r) { results.add(r); return r.toolCallId().equals("c1"); }
                return false;
            })
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> {
                if (e instanceof QueryToolResultEvent r) { results.add(r); return r.toolCallId().equals("c2"); }
                return false;
            })
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> {
                if (e instanceof QueryToolResultEvent r) { results.add(r); return r.toolCallId().equals("c3"); }
                return false;
            })
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent
                && ((QueryTextDeltaEvent) e).text().equals("done"))
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();

        assertThat(results).hasSize(3);
        assertThat(results).extracting(QueryToolResultEvent::toolCallId)
            .containsExactly("c1", "c2", "c3");

        // No concurrent execution: strict sequential order enforced by Semaphore
        assertThat(orderLog).containsExactly(
            "start:c1", "end:c1",
            "start:c2", "end:c2",
            "start:c3", "end:c3"
        );
    }

    @Test
    void maxToolConcurrencyTwoLimitsParallelReadOnlyToTwo() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(
                    new ModelStreamStartedEvent("glm-5.1"),
                    new ModelToolUseEvent("c1", "file_read", "/a"),
                    new ModelToolUseEvent("c2", "file_read", "/b"),
                    new ModelToolUseEvent("c3", "file_read", "/c"),
                    new ModelToolUseEvent("c4", "file_read", "/d"),
                    new ModelCompletedEvent()
                );
            }
            return Flux.just(
                new ModelTextDeltaEvent("done"),
                new ModelCompletedEvent()
            );
        };

        var active = new java.util.concurrent.atomic.AtomicInteger(0);
        var peakActive = new java.util.concurrent.atomic.AtomicInteger(0);

        ToolExecutor countingExecutor = (req, ctx) ->
            Mono.defer(() -> {
                int now = active.incrementAndGet();
                peakActive.updateAndGet(p -> Math.max(p, now));
                return Mono.delay(java.time.Duration.ofMillis(50))
                    .thenReturn(ToolResult.success(req.toolCallId(), req.toolName(),
                        "result:" + req.input()))
                    .doOnSuccess(v -> active.decrementAndGet());
            });

        // Explicit maxToolConcurrency=2
        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, countingExecutor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills,
            new ToolPermissionProperties(false, ToolPermissionProperties.Mode.ALLOWLIST, null),
            new SystemPromptBuilder(), 10, 2);

        var command = new TurnCommand(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "go")),
            "glm-5.1", null, List.of());

        java.util.List<QueryToolResultEvent> results = new java.util.ArrayList<>();
        java.util.List<QueryToolUseSummaryEvent> summaries = new java.util.ArrayList<>();

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolCallId().equals("c1"))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolCallId().equals("c2"))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolCallId().equals("c3"))
            .expectNextMatches(e -> e instanceof QueryToolCallRequestedEvent r
                && r.toolCallId().equals("c4"))
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> {
                if (e instanceof QueryToolResultEvent r) { results.add(r); return r.toolCallId().equals("c1"); }
                return false;
            })
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> {
                if (e instanceof QueryToolResultEvent r) { results.add(r); return r.toolCallId().equals("c2"); }
                return false;
            })
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> {
                if (e instanceof QueryToolResultEvent r) { results.add(r); return r.toolCallId().equals("c3"); }
                return false;
            })
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> {
                if (e instanceof QueryToolResultEvent r) { results.add(r); return r.toolCallId().equals("c4"); }
                return false;
            })
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> {
                if (e instanceof QueryToolUseSummaryEvent s) {
                    summaries.add(s);
                    return s.totalToolCalls() == 4
                        && s.compactedResults() == 0
                        && s.errorResults() == 0;
                }
                return false;
            })
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .thenConsumeWhile(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent
                && ((QueryTextDeltaEvent) e).text().equals("done"))
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();

        assertThat(results).hasSize(4);
        assertThat(summaries).hasSize(1);
        assertThat(results).extracting(QueryToolResultEvent::toolCallId)
            .containsExactly("c1", "c2", "c3", "c4");

        // Concurrent execution detected (at least 2 ran at once)
        assertThat(peakActive.get()).as("concurrent read-only execution")
            .isGreaterThan(1);
        // But bounded by configured maxToolConcurrency=2
        assertThat(peakActive.get()).as("bounded by maxToolConcurrency")
            .isLessThanOrEqualTo(2);
    }

    @Test
    void promptTooLongCleanFailureDoesNotCallModel() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient modelClient = request -> {
            modelCalls.incrementAndGet();
            return Flux.error(new AssertionError("Model should not be called"));
        };
        ToolExecutor executor = (req, ctx) -> Mono.error(new AssertionError("No tools expected"));
        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills,
            new ToolPermissionProperties(false, ToolPermissionProperties.Mode.ALLOWLIST, null),
            new SystemPromptBuilder(), 10, 4, new ToolResultBudget(12000, 4000), 4,
            10, false);

        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "large prompt")),
            "glm-5.1", null, List.of());

        List<QueryEvent> events = orchestrator.runTurn(command).collectList().block();
        assertThat(events).isNotNull();

        assertThat(modelCalls.get()).isZero();
        assertThat(events).hasSize(5);
        assertThat(events.get(0)).isInstanceOf(QueryTextDeltaEvent.class);
        assertThat(((QueryTextDeltaEvent) events.get(0)).text())
            .isEqualTo(ContextBudgetGuard.FAILURE_MESSAGE);
        assertThat(events.get(1)).isEqualTo(new QueryStopReasonEvent("context_too_large"));
        assertThat(events.get(2)).isInstanceOf(QueryTranscriptUpdateEvent.class);
        var update = (QueryTranscriptUpdateEvent) events.get(2);
        assertThat(update.update().messagesToPersist()).hasSize(1);
        assertThat(update.update().messagesToPersist().get(0)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) update.update().messagesToPersist().get(0)).textContent())
            .isEqualTo(ContextBudgetGuard.FAILURE_MESSAGE);
        assertThat(events.get(3)).isInstanceOf(QueryResultEvent.class);
        var result = (QueryResultEvent) events.get(3);
        assertThat(result.success()).isFalse();
        assertThat(result.stopReason()).isEqualTo("context_too_large");
        assertThat(events.get(4)).isInstanceOf(QueryCompletedEvent.class);
    }

    @Test
    void autoCompactsOversizedHistoryBeforeModelRequest() {
        String rawMarker = "RAW_MIDDLE_MARKER_SHOULD_NOT_REACH_PROVIDER";
        String rawHugeToolOutput = "head " + "x".repeat(12000) + rawMarker + " y".repeat(12000);
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();
        List<AuditEvent> auditEvents = new ArrayList<>();
        AuditTrail captureAudit = event -> {
            auditEvents.add(event);
            return Mono.empty();
        };
        ModelClient modelClient = request -> {
            modelCalls.incrementAndGet();
            capturedRequest.set(request);
            return Flux.just(new ModelTextDeltaEvent("done"), new ModelCompletedEvent());
        };
        ToolExecutor executor = (req, ctx) -> Mono.error(new AssertionError("No tools expected"));
        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, executor, noopRegistry, new InMemoryTranscriptStore(captureAudit),
            captureAudit, noopMetrics, noopSkills,
            new ToolPermissionProperties(false, ToolPermissionProperties.Mode.ALLOWLIST, null),
            new SystemPromptBuilder(), 10, 4, new ToolResultBudget(12000, 4000), 4,
            10000, true, 1);

        List<Message> history = List.of(
            new UserMessage(UUID.randomUUID(), Instant.now(), "first user prompt"),
            new AssistantMessage(UUID.randomUUID(), Instant.now(), "first assistant answer"),
            new ToolResultMessage(UUID.randomUUID(), Instant.now(), "c1", "file_read", rawHugeToolOutput, false),
            new UserMessage(UUID.randomUUID(), Instant.now(), "recent tail prompt"));
        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            history,
            "glm-5.1", null, List.of());

        List<QueryEvent> events = orchestrator.runTurn(command).collectList().block();
        assertThat(events).isNotNull();

        assertThat(modelCalls.get()).isEqualTo(1);
        assertThat(events.stream().noneMatch(QueryToolUseSummaryEvent.class::isInstance)).isTrue();
        assertThat(capturedRequest.get()).isNotNull();
        List<Message> providerMessages = capturedRequest.get().messages();
        assertThat(providerMessages).hasSize(2);
        assertThat(providerMessages.get(0)).isInstanceOf(UserMessage.class);
        String summary = ((UserMessage) providerMessages.get(0)).content();
        assertThat(summary).contains("[conversation compacted]");
        assertThat(summary).contains("omitted_messages: 3");
        assertThat(summary).contains("omitted_tool_result_messages: 1");
        assertThat(summary).doesNotContain(rawMarker);
        assertThat(providerMessages.get(1)).isSameAs(history.get(3));
        assertThat(providerMessages.toString()).doesNotContain(rawMarker);
        assertThat(auditEvents).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("model.context.compacted");
            assertThat(event.attributes()).containsKeys("beforeChars", "afterChars", "preservedMessages");
            assertThat(event.attributes().get("preservedMessages")).isEqualTo(1);
        });
    }

    @Test
    void compactBoundaryPreservesSubsequentToolLoop() {
        String rawMarker = "RAW_COMPACT_BOUNDARY_MARKER_SHOULD_NOT_REACH_PROVIDER";
        String rawHugeToolOutput = "old " + "x".repeat(12000) + rawMarker + " y".repeat(12000);
        AtomicInteger modelCalls = new AtomicInteger();
        List<ModelRequest> capturedRequests = new ArrayList<>();
        ModelClient modelClient = request -> {
            capturedRequests.add(request);
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(
                    new ModelToolUseEvent("read-current", "file_read", Map.of("path", "current.txt")),
                    new ModelCompletedEvent());
            }
            return Flux.just(
                new ModelTextDeltaEvent("final answer"),
                new ModelCompletedEvent());
        };
        ToolExecutor executor = (req, ctx) -> Mono.just(
            ToolResult.success(req.toolCallId(), req.toolName(), "fresh file content"));
        var orchestrator = new DefaultQueryOrchestrator(
            modelClient, executor, noopRegistry, new InMemoryTranscriptStore(noopAudit),
            noopAudit, noopMetrics, noopSkills,
            new ToolPermissionProperties(false, ToolPermissionProperties.Mode.ALLOWLIST, null),
            new SystemPromptBuilder(), 10, 4, new ToolResultBudget(12000, 4000), 4,
            10000, true, 1);

        UserMessage currentUser = new UserMessage(UUID.randomUUID(), Instant.now(), "please read current.txt");
        List<Message> history = List.of(
            new UserMessage(UUID.randomUUID(), Instant.now(), "old user"),
            new AssistantMessage(UUID.randomUUID(), Instant.now(), "old assistant"),
            new ToolResultMessage(UUID.randomUUID(), Instant.now(), "old-call", "file_read", rawHugeToolOutput, false),
            currentUser);
        var command = new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            history,
            "glm-5.1", null, List.of());

        List<QueryEvent> events = orchestrator.runTurn(command).collectList().block();
        assertThat(events).isNotNull();

        assertThat(modelCalls.get()).isEqualTo(2);
        assertThat(capturedRequests).hasSize(2);

        List<Message> firstRound = capturedRequests.get(0).messages();
        assertThat(firstRound).hasSize(2);
        assertThat(firstRound.get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) firstRound.get(0)).content()).contains("[conversation compacted]");
        assertThat(firstRound.get(1)).isSameAs(currentUser);
        assertThat(firstRound.toString()).doesNotContain(rawMarker);

        List<Message> secondRound = capturedRequests.get(1).messages();
        assertThat(secondRound).hasSize(4);
        assertThat(secondRound.get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) secondRound.get(0)).content()).contains("[conversation compacted]");
        assertThat(secondRound.get(1)).isSameAs(currentUser);
        assertThat(secondRound.get(2)).isInstanceOf(AssistantMessage.class);
        AssistantMessage assistantToolUse = (AssistantMessage) secondRound.get(2);
        assertThat(assistantToolUse.content())
            .anySatisfy(block -> {
                assertThat(block).isInstanceOf(AssistantToolUseBlock.class);
                assertThat(((AssistantToolUseBlock) block).id()).isEqualTo("read-current");
                assertThat(((AssistantToolUseBlock) block).name()).isEqualTo("file_read");
            });
        assertThat(secondRound.get(3)).isInstanceOf(ToolResultMessage.class);
        ToolResultMessage toolResult = (ToolResultMessage) secondRound.get(3);
        assertThat(toolResult.toolCallId()).isEqualTo("read-current");
        assertThat(toolResult.toolName()).isEqualTo("file_read");
        assertThat(toolResult.content()).isEqualTo("fresh file content");
        assertThat(secondRound.toString()).doesNotContain(rawMarker);

        assertThat(events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(QueryTextDeltaEvent.class);
            assertThat(((QueryTextDeltaEvent) event).text()).isEqualTo("final answer");
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(QueryResultEvent.class);
            assertThat(((QueryResultEvent) event).success()).isTrue();
        });
    }

    private static String extractTaskId(String summary) {
        int idx = summary.indexOf("task_id=");
        if (idx < 0) return "";
        String sub = summary.substring(idx + 8);
        int end = sub.indexOf(',');
        int endBrace = sub.indexOf('}');
        if (end < 0) end = endBrace;
        else if (endBrace > 0 && endBrace < end) end = endBrace;
        return (end > 0 ? sub.substring(0, end) : sub).trim();
    }

    private static DefaultQueryOrchestrator orchestratorWithStopHook(
        ModelClient modelClient,
        ToolExecutionHook hook
    ) {
        return orchestratorWithStopHook(modelClient, hook, 10);
    }

    private static DefaultQueryOrchestrator orchestratorWithStopHook(
        ModelClient modelClient,
        ToolExecutionHook hook,
        int maxToolRounds
    ) {
        ToolExecutor toolExecutor = (req, ctx) -> Mono.error(new AssertionError("No tools expected"));
        return new DefaultQueryOrchestrator(
            modelClient,
            toolExecutor,
            noopRegistry,
            new InMemoryTranscriptStore(noopAudit),
            noopAudit,
            noopMetrics,
            noopSkills,
            new ToolPermissionProperties(false, ToolPermissionProperties.Mode.ALLOWLIST, null),
            new SystemPromptBuilder(),
            maxToolRounds,
            4,
            new ToolResultBudget(12000, 4000),
            4,
            240000,
            true,
            12,
            2,
            new ToolHookPipeline(List.of(hook)));
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
        }
    }

    private static Tool stubTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public ToolDefinition definition() {
                return new ToolDefinition(name, "test tool", Map.of());
            }
            @Override public Mono<Object> execute(Object input, Object context) {
                return Mono.just("ok");
            }
        };
    }

    private static Tool toolWithDefinition(
        ToolDefinition definition,
        java.util.function.Function<Object, Mono<Object>> executor
    ) {
        return new Tool() {
            @Override public String name() { return definition.name(); }
            @Override public ToolDefinition definition() { return definition; }
            @Override public Mono<Object> execute(Object input, Object context) {
                return executor.apply(input);
            }
        };
    }

    private static String stripTimestamp(String prompt) {
        return prompt.replaceAll("- Current time: .+", "- Current time: <redacted>");
    }

    private static int indexOfEvent(List<QueryEvent> events, java.util.function.Predicate<QueryEvent> predicate) {
        for (int i = 0; i < events.size(); i++) {
            if (predicate.test(events.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
