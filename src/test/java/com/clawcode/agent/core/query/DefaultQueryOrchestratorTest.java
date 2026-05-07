package com.clawcode.agent.core.query;

import com.clawcode.agent.forensics.AuditTrail;
import com.clawcode.agent.forensics.ObservabilityMetrics;
import com.clawcode.agent.model.*;
import com.clawcode.agent.persistence.InMemoryTranscriptStore;
import com.clawcode.agent.shared.message.UserMessage;
import com.clawcode.agent.skills.SkillContent;
import com.clawcode.agent.skills.SkillContextService;
import com.clawcode.agent.skills.SkillDefinition;
import com.clawcode.agent.skills.SkillRegistry;
import com.clawcode.agent.tools.*;
import com.clawcode.agent.tools.hooks.TestToolExecutionHook;
import com.clawcode.agent.tools.hooks.ToolHookPipeline;
import com.clawcode.agent.tools.hooks.ToolStopHookResult;
import com.clawcode.agent.tools.security.WorkspacePathGuard;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultQueryOrchestratorTest {

    private static final AuditTrail noopAudit = event -> Mono.empty();
    private static final ObservabilityMetrics noopMetrics =
        new ObservabilityMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    private static final SkillContextService noopSkills = new SkillContextService(null);
    private static final ToolRegistry noopRegistry = new ToolRegistry() {
        @Override public java.util.Optional<Tool> findByName(String name) { return java.util.Optional.empty(); }
        @Override public java.util.Set<String> listNames() { return java.util.Set.of(); }
    };

    private final InMemoryTranscriptStore transcriptStore = new InMemoryTranscriptStore(noopAudit);

    @Test
    void mapsTextDeltaEvent() {
        var orchestrator = orchestratorWithEvents(
            new ModelStreamStartedEvent("m"),
            new ModelTextDeltaEvent("noop"),
            new ModelCompletedEvent()
        );

        var command = turnCommand();

        StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent delta && delta.text().equals("noop"))
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent r
                && r.success()
                && "end_turn".equals(r.stopReason())
                && r.numTurns() >= 1
                && r.permissionDenials() == 0
                && r.durationMs() >= 0)
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();
    }

    @Test
    void stopsCollectingModelStreamAtCompletedEvent() {
        ModelClient client = request -> Flux.<ModelEvent>just(
                new ModelStreamStartedEvent("m"),
                new ModelTextDeltaEvent("done"),
                new ModelCompletedEvent())
            .concatWith(Flux.<ModelEvent>never());
        var orchestrator = new DefaultQueryOrchestrator(
            client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, noopSkills);

        StepVerifier.create(orchestrator.runTurn(turnCommand()))
            .expectNextMatches(e -> e instanceof QueryStreamStartedEvent)
            .expectNextMatches(e -> e instanceof QueryTextDeltaEvent delta && delta.text().equals("done"))
            .expectNextMatches(e -> e instanceof QueryTranscriptUpdateEvent)
            .expectNextMatches(e -> e instanceof QueryResultEvent r && r.success())
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();
    }

    @Test
    void mapsModelErrorEventToQueryError() {
        var orchestrator = orchestratorWithEvents(
            new ModelErrorEvent("Overloaded", "overloaded_error")
        );

        StepVerifier.create(orchestrator.runTurn(turnCommand()))
            .expectNextMatches(e -> e instanceof QueryErrorEvent qe
                && qe.message().equals("Overloaded")
                && qe.code().equals("overloaded_error")
                && qe.source().equals("model"))
            .expectNextMatches(e -> e instanceof QueryResultEvent r
                && !r.success()
                && "overloaded_error".equals(r.stopReason())
                && r.numTurns() >= 1
                && r.permissionDenials() == 0
                && r.durationMs() >= 0)
            .verifyComplete();
    }

    @Test
    void resultContainsUsageAndStopReasonFromModelEvents() {
        var orchestrator = orchestratorWithEvents(
            new ModelStopReasonEvent("end_turn"),
            new ModelUsageEvent(100L, 42L),
            new ModelCompletedEvent()
        );

        StepVerifier.create(orchestrator.runTurn(turnCommand()))
            .expectNextMatches(e -> e instanceof QueryStopReasonEvent
                && ((QueryStopReasonEvent) e).reason().equals("end_turn"))
            .expectNextMatches(e -> e instanceof QueryUsageEvent u
                && u.inputTokens().equals(100L)
                && u.outputTokens().equals(42L))
            .expectNextMatches(e -> e instanceof QueryResultEvent r
                && r.success()
                && "end_turn".equals(r.stopReason())
                && r.usage() != null
                && r.usage().inputTokens().equals(100L)
                && r.usage().outputTokens().equals(42L))
            .expectNextMatches(e -> e instanceof QueryCompletedEvent)
            .verifyComplete();
    }

    @Test
    void resultWithErrorContainsPriorUsage() {
        var orchestrator = orchestratorWithEvents(
            new ModelUsageEvent(50L, 25L),
            new ModelErrorEvent("Fail", "fail_error")
        );

        StepVerifier.create(orchestrator.runTurn(turnCommand()))
            .expectNextMatches(e -> e instanceof QueryUsageEvent u
                && u.inputTokens().equals(50L)
                && u.outputTokens().equals(25L))
            .expectNextMatches(e -> e instanceof QueryErrorEvent qe
                && qe.message().equals("Fail")
                && qe.code().equals("fail_error")
                && qe.source().equals("model"))
            .expectNextMatches(e -> e instanceof QueryResultEvent r
                && !r.success()
                && "fail_error".equals(r.stopReason())
                && r.usage() != null
                && r.usage().inputTokens().equals(50L)
                && r.usage().outputTokens().equals(25L))
            .verifyComplete();
    }

    @Test
    void stopHookFailEmitsCleanUserVisibleStopWithoutRetryOrDuplicateText() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient client = request -> {
            modelCalls.incrementAndGet();
            return Flux.just(
                new ModelTextDeltaEvent("provider stopped text"),
                new ModelStopReasonEvent("end_turn"),
                new ModelCompletedEvent());
        };
        var hookContext = new UserMessage(UUID.randomUUID(), Instant.now(), "hook failure context");
        var hook = TestToolExecutionHook.builder()
            .stop(context -> Mono.just(ToolStopHookResult.fail(
                "Stopped by hook", "hook_stop", List.of(hookContext))))
            .build();
        var orchestrator = new DefaultQueryOrchestrator(
            client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, noopSkills,
            new ToolPermissionProperties(false, ToolPermissionProperties.Mode.ALLOWLIST, null),
            new com.clawcode.agent.core.prompt.SystemPromptBuilder(),
            10, 4, new ToolResultBudget(12000, 4000), 4,
            240000, true, 12, 2,
            new ToolHookPipeline(List.of(hook)));

        List<QueryEvent> events = orchestrator.runTurn(turnCommand()).collectList().block();

        assertThat(events).isNotNull();
        assertThat(modelCalls.get()).isEqualTo(1);

        List<String> publicText = events.stream()
            .filter(QueryTextDeltaEvent.class::isInstance)
            .map(QueryTextDeltaEvent.class::cast)
            .map(QueryTextDeltaEvent::text)
            .toList();
        assertThat(publicText).containsExactly("Stopped by hook");

        int textIdx = indexOfEvent(events, e -> e instanceof QueryTextDeltaEvent d
            && d.text().equals("Stopped by hook"));
        int resultIdx = indexOfEvent(events, e -> e instanceof QueryResultEvent r
            && !r.success() && "hook_stop".equals(r.stopReason()));
        int completedIdx = indexOfEvent(events, QueryCompletedEvent.class::isInstance);

        assertThat(textIdx).isGreaterThanOrEqualTo(0);
        assertThat(resultIdx).isGreaterThan(textIdx);
        assertThat(completedIdx).isGreaterThan(resultIdx);
        assertThat(events).anySatisfy(e -> {
            assertThat(e).isInstanceOf(QueryStopReasonEvent.class);
            assertThat(((QueryStopReasonEvent) e).reason()).isEqualTo("hook_stop");
        });
        assertThat(events.toString()).doesNotContain("provider stopped text");
    }

    @Test
    void mapsModelStopReasonEventToQueryStopReason() {
        var orchestrator = orchestratorWithEvents(
            new ModelStopReasonEvent("end_turn")
        );

        StepVerifier.create(orchestrator.runTurn(turnCommand()))
            .expectNextMatches(e -> e instanceof QueryStopReasonEvent qe
                && qe.reason().equals("end_turn"))
            .verifyComplete();
    }

    @Test
    void maxOutputStopRetriesWithResumeMetaMessage() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<ModelRequest> firstRequest = new AtomicReference<>();
        AtomicReference<ModelRequest> secondRequest = new AtomicReference<>();
        ModelClient client = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                firstRequest.set(request);
                return Flux.just(
                    new ModelTextDeltaEvent("partial "),
                    new ModelStopReasonEvent("max_tokens"),
                    new ModelCompletedEvent());
            }
            secondRequest.set(request);
            return Flux.just(
                new ModelTextDeltaEvent("continuation"),
                new ModelCompletedEvent());
        };
        var orchestrator = new DefaultQueryOrchestrator(
            client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, noopSkills);

        List<QueryEvent> events = orchestrator.runTurn(turnCommand()).collectList().block();
        assertThat(events).isNotNull();

        assertThat(modelCalls.get()).isEqualTo(2);
        assertThat(firstRequest.get()).isNotNull();
        assertThat(secondRequest.get()).isNotNull();
        assertThat(firstRequest.get().messages()).hasSize(1);
        assertThat(firstRequest.get().messages().toString()).doesNotContain("[continuation request]");

        List<com.clawcode.agent.shared.message.Message> secondMessages = secondRequest.get().messages();
        assertThat(secondMessages).hasSize(3);
        assertThat(secondMessages.get(1)).isInstanceOf(com.clawcode.agent.shared.message.AssistantMessage.class);
        assertThat(((com.clawcode.agent.shared.message.AssistantMessage) secondMessages.get(1)).textContent())
            .isEqualTo("partial ");
        assertThat(secondMessages.get(2)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) secondMessages.get(2)).content())
            .contains("[continuation request]")
            .contains("Resume directly from the exact point where you stopped");

        assertThat(events.stream()
            .filter(QueryTextDeltaEvent.class::isInstance)
            .map(QueryTextDeltaEvent.class::cast)
            .map(QueryTextDeltaEvent::text)
            .toList()).containsExactly("partial ", "continuation");
        assertThat(events.toString()).doesNotContain("[continuation request]");
        assertThat(events.stream().noneMatch(e -> e instanceof QueryStopReasonEvent stop
            && "max_tokens".equals(stop.reason()))).isTrue();
        assertThat(events).anySatisfy(e -> {
            assertThat(e).isInstanceOf(QueryResultEvent.class);
            assertThat(((QueryResultEvent) e).success()).isTrue();
        });
    }

    @Test
    void maxOutputRecoveryCombinesPartialAndContinuation() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient client = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(
                    new ModelTextDeltaEvent("partial "),
                    new ModelStopReasonEvent("max_output_tokens"),
                    new ModelCompletedEvent());
            }
            return Flux.just(
                new ModelTextDeltaEvent("continuation"),
                new ModelCompletedEvent());
        };
        var orchestrator = new DefaultQueryOrchestrator(
            client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, noopSkills);

        List<QueryEvent> events = orchestrator.runTurn(turnCommand()).collectList().block();
        assertThat(events).isNotNull();

        assertThat(modelCalls.get()).isEqualTo(2);
        assertThat(events.stream()
            .filter(QueryTextDeltaEvent.class::isInstance)
            .map(QueryTextDeltaEvent.class::cast)
            .map(QueryTextDeltaEvent::text)
            .toList()).containsExactly("partial ", "continuation");

        List<TurnTranscriptUpdate> updates = events.stream()
            .filter(QueryTranscriptUpdateEvent.class::isInstance)
            .map(QueryTranscriptUpdateEvent.class::cast)
            .map(QueryTranscriptUpdateEvent::update)
            .toList();
        assertThat(updates).hasSize(1);
        assertThat(updates.getFirst().messagesToPersist()).hasSize(1);
        assertThat(updates.getFirst().messagesToPersist().getFirst())
            .isInstanceOf(com.clawcode.agent.shared.message.AssistantMessage.class);
        var persisted = (com.clawcode.agent.shared.message.AssistantMessage)
            updates.getFirst().messagesToPersist().getFirst();
        assertThat(persisted.textContent()).isEqualTo("partial continuation");
        assertThat(persisted.textContent().indexOf("partial "))
            .isEqualTo(persisted.textContent().lastIndexOf("partial "));
    }

    @Test
    void maxOutputRecoveryStopsAfterLimit() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient client = request -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(
                    new ModelTextDeltaEvent("partial one "),
                    new ModelStopReasonEvent("max_tokens"),
                    new ModelCompletedEvent());
            }
            return Flux.just(
                new ModelTextDeltaEvent("partial two"),
                new ModelStopReasonEvent("max_tokens"),
                new ModelCompletedEvent());
        };
        var orchestrator = new DefaultQueryOrchestrator(
            client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, noopSkills,
            new ToolPermissionProperties(false, ToolPermissionProperties.Mode.ALLOWLIST, null),
            new com.clawcode.agent.core.prompt.SystemPromptBuilder(),
            10, 4, new ToolResultBudget(12000, 4000), 4,
            240000, true, 12, 1);

        List<QueryEvent> events = orchestrator.runTurn(turnCommand()).collectList().block();
        assertThat(events).isNotNull();

        assertThat(modelCalls.get()).isEqualTo(2);
        assertThat(events.stream()
            .filter(QueryTextDeltaEvent.class::isInstance)
            .map(QueryTextDeltaEvent.class::cast)
            .map(QueryTextDeltaEvent::text)
            .toList()).containsExactly(
                "partial one partial two\n\n"
                    + "The response stopped because max_output_tokens was reached before completion. "
                    + "Start a new turn to continue.");
        assertThat(events).anySatisfy(e -> {
            assertThat(e).isInstanceOf(QueryStopReasonEvent.class);
            assertThat(((QueryStopReasonEvent) e).reason()).isEqualTo("max_output_tokens");
        });
        assertThat(events).anySatisfy(e -> {
            assertThat(e).isInstanceOf(QueryResultEvent.class);
            assertThat(((QueryResultEvent) e).success()).isFalse();
            assertThat(((QueryResultEvent) e).stopReason()).isEqualTo("max_output_tokens");
        });
        assertThat(events.getLast()).isInstanceOf(QueryCompletedEvent.class);

        List<TurnTranscriptUpdate> updates = events.stream()
            .filter(QueryTranscriptUpdateEvent.class::isInstance)
            .map(QueryTranscriptUpdateEvent.class::cast)
            .map(QueryTranscriptUpdateEvent::update)
            .toList();
        assertThat(updates).hasSize(1);
        var persisted = (com.clawcode.agent.shared.message.AssistantMessage)
            updates.getFirst().messagesToPersist().getFirst();
        assertThat(persisted.textContent()).contains("partial one partial two");
        assertThat(persisted.textContent()).contains("max_output_tokens was reached before completion");
    }

    @Test
    void mapsModelUsageEventToQueryUsage() {
        var orchestrator = orchestratorWithEvents(
            new ModelUsageEvent(100L, 42L)
        );

        StepVerifier.create(orchestrator.runTurn(turnCommand()))
            .expectNextMatches(e -> e instanceof QueryUsageEvent qe
                && qe.inputTokens().equals(100L)
                && qe.outputTokens().equals(42L)
                && qe.totalTokens().equals(142L))
            .verifyComplete();
    }

    @Test
    void unknownModelEventMapsToQueryError() {
        var orchestrator = orchestratorWithEvents(
            new ModelEvent() {}
        );

        StepVerifier.create(orchestrator.runTurn(turnCommand()))
            .expectNextMatches(e -> e instanceof QueryErrorEvent qe
                && qe.code().equals("UNMAPPED_MODEL_EVENT")
                && qe.source().equals("orchestrator"))
            .verifyComplete();
    }

    private final ToolExecutor noopExecutor = (req, ctx) -> Mono.just(
        ToolResult.success(req.toolCallId(), req.toolName(), "noop-result"));

    private DefaultQueryOrchestrator orchestratorWithEvents(ModelEvent... events) {
        ModelClient client = request -> Flux.fromArray(events);
        return new DefaultQueryOrchestrator(client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, noopSkills);
    }

    private static int indexOfEvent(List<QueryEvent> events, java.util.function.Predicate<QueryEvent> predicate) {
        for (int i = 0; i < events.size(); i++) {
            if (predicate.test(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private TurnCommand turnCommand() {
        return new TurnCommand(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
            "test-model",
            "system",
            List.of()
        );
    }

    @Nested
    class SkillInjection {

        private SkillRegistry stubRegistry(java.util.function.Function<String, Mono<SkillContent>> readFn) {
            return new SkillRegistry() {
                @Override public Flux<SkillDefinition> list() { return Flux.empty(); }
                @Override public Mono<SkillContent> read(String id) { return readFn.apply(id); }
            };
        }

        @Test
        void skillEnrichesSystemPrompt() {
            SkillContextService skillService = new SkillContextService(
                stubRegistry(id -> Mono.just(new SkillContent(id, "Test Skill", "Always respond in French."))));

            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            ModelClient client = request -> {
                capturedPrompt.set(request.systemPrompt());
                return Flux.just(new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, skillService);

            var command = new TurnCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
                "test-model", "base system", List.of("french-skill"));

            StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            assertThat(capturedPrompt.get()).contains("base system");
            assertThat(capturedPrompt.get()).contains("## Active Skills");
            assertThat(capturedPrompt.get()).contains("Always respond in French.");
            assertThat(capturedPrompt.get()).contains("--- Skill: french-skill ---");
        }

        @Test
        void unknownSkillProducesControlledErrorInContext() {
            SkillContextService skillService = new SkillContextService(
                stubRegistry(id -> Mono.error(
                    new java.nio.file.NoSuchFileException("Skill not found: " + id))));

            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            ModelClient client = request -> {
                capturedPrompt.set(request.systemPrompt());
                return Flux.just(new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, skillService);

            var command = new TurnCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
                "test-model", "system", List.of("nonexistent-skill"));

            StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            assertThat(capturedPrompt.get()).contains("[ERROR: skill 'nonexistent-skill' not found]");
        }

        @Test
        void emptySkillIdsLeavesSystemPromptUnchanged() {
            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            ModelClient client = request -> {
                capturedPrompt.set(request.systemPrompt());
                return Flux.just(new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, noopSkills);

            var command = new TurnCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
                "test-model", "original prompt", List.of());

            StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            assertThat(capturedPrompt.get()).contains("original prompt");
        }

        @Test
        void nullSkillIdsLeavesSystemPromptUnchanged() {
            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            ModelClient client = request -> {
                capturedPrompt.set(request.systemPrompt());
                return Flux.just(new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, noopSkills);

            var command = new TurnCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
                "test-model", "original prompt", null);

            StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            assertThat(capturedPrompt.get()).contains("## Custom Instructions");
            assertThat(capturedPrompt.get()).contains("original prompt");
        }

        @Test
        void multipleSkillsAreAllInjected() {
            SkillContextService skillService = new SkillContextService(
                stubRegistry(id -> Mono.just(new SkillContent(id, id, "Content for " + id))));

            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            ModelClient client = request -> {
                capturedPrompt.set(request.systemPrompt());
                return Flux.just(new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, skillService);

            var command = new TurnCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
                "test-model", "base", List.of("skill-a", "skill-b"));

            StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            String prompt = capturedPrompt.get();
            assertThat(prompt).contains("--- Skill: skill-a ---");
            assertThat(prompt).contains("Content for skill-a");
            assertThat(prompt).contains("--- Skill: skill-b ---");
            assertThat(prompt).contains("Content for skill-b");
        }

        @Test
        void skillWithoutBaseSystemPromptOnlyContainsSkill() {
            SkillContextService skillService = new SkillContextService(
                stubRegistry(id -> Mono.just(new SkillContent(id, "S", "Skill-only content"))));

            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            ModelClient client = request -> {
                capturedPrompt.set(request.systemPrompt());
                return Flux.just(new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, skillService);

            var command = new TurnCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
                "test-model", null, List.of("my-skill"));

            StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            String prompt = capturedPrompt.get();
            assertThat(prompt).contains("## Active Skills");
            assertThat(prompt).contains("Skill-only content");
            assertThat(prompt).doesNotContain("null");
        }

        @Test
        void blankBaseSystemPromptOnlyContainsSkill() {
            SkillContextService skillService = new SkillContextService(
                stubRegistry(id -> Mono.just(new SkillContent(id, "S", "Skill-only content"))));

            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            ModelClient client = request -> {
                capturedPrompt.set(request.systemPrompt());
                return Flux.just(new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, skillService);

            var command = new TurnCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
                "test-model", "   ", List.of("my-skill"));

            StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            String prompt = capturedPrompt.get();
            assertThat(prompt).contains("## Active Skills");
            assertThat(prompt).doesNotContain("   ");
        }
    }

    @Nested
    class DynamicToolPublication {

        @Test
        void modelRequestReceivesToolsFromRegistry() {
            AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();
            ModelClient client = request -> {
                capturedRequest.set(request);
                return Flux.just(new ModelCompletedEvent());
            };

            ToolRegistry registry = new ToolRegistry() {
                @Override public Optional<Tool> findByName(String name) { return Optional.empty(); }
                @Override public Set<String> listNames() { return Set.of("custom_a", "custom_b"); }
                @Override public List<ToolDefinition> definitions() {
                    return List.of(
                        new ToolDefinition("custom_a", "Tool A", Map.of("type", "object", "properties", Map.of())),
                        new ToolDefinition("custom_b", "Tool B", Map.of("type", "object", "properties", Map.of()))
                    );
                }
            };

            var orchestrator = new DefaultQueryOrchestrator(
                client, noopExecutor, registry, transcriptStore, noopAudit, noopMetrics, noopSkills);

            StepVerifier.create(orchestrator.runTurn(turnCommand()))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            ModelRequest req = capturedRequest.get();
            assertThat(req.tools()).hasSize(2);
            assertThat(req.tools().stream().map(ModelToolDefinition::name))
                .containsExactly("custom_a", "custom_b");
        }

        @Test
        void emptyRegistrySendsNoTools() {
            AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();
            ModelClient client = request -> {
                capturedRequest.set(request);
                return Flux.just(new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, noopSkills);

            StepVerifier.create(orchestrator.runTurn(turnCommand()))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            assertThat(capturedRequest.get().tools()).isEmpty();
        }

        @Test
        void toolSchemasArePassedThroughToModelRequest() {
            AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();
            ModelClient client = request -> {
                capturedRequest.set(request);
                return Flux.just(new ModelCompletedEvent());
            };

            Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")),
                "required", List.of("path"),
                "additionalProperties", false
            );

            ToolRegistry registry = new ToolRegistry() {
                @Override public Optional<Tool> findByName(String name) { return Optional.empty(); }
                @Override public Set<String> listNames() { return Set.of("file_read"); }
                @Override public List<ToolDefinition> definitions() {
                    return List.of(new ToolDefinition("file_read", "Read a file", schema));
                }
            };

            var orchestrator = new DefaultQueryOrchestrator(
                client, noopExecutor, registry, transcriptStore, noopAudit, noopMetrics, noopSkills);

            StepVerifier.create(orchestrator.runTurn(turnCommand()))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            ModelToolDefinition toolDef = capturedRequest.get().tools().getFirst();
            assertThat(toolDef.name()).isEqualTo("file_read");
            assertThat(toolDef.description()).isEqualTo("Read a file");
            assertThat(toolDef.inputSchema()).isEqualTo(schema);
        }
    }

    @Nested
    class RuntimePromptContract {

        @Test
        void systemPromptIsAlwaysNonBlank() {
            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            ModelClient client = request -> {
                capturedPrompt.set(request.systemPrompt());
                return Flux.just(new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, noopSkills);

            var command = new TurnCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
                "test-model", null, null);

            StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            assertThat(capturedPrompt.get()).isNotBlank();
        }

        @Test
        void promptContainsRuntimeSectionsEvenWithNullCustomPrompt() {
            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            ModelClient client = request -> {
                capturedPrompt.set(request.systemPrompt());
                return Flux.just(new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, noopSkills);

            var command = new TurnCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
                "test-model", null, null);

            StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            String prompt = capturedPrompt.get();
            assertThat(prompt).contains("## Environment");
            assertThat(prompt).contains("## Behavioral Rules");
            assertThat(prompt).contains("## Truthful Reporting");
            assertThat(prompt).doesNotContain("## Custom Instructions");
            assertThat(prompt).doesNotContain("## Active Skills");
        }

        @Test
        void customPromptDoesNotReplaceRuntimeSections() {
            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            ModelClient client = request -> {
                capturedPrompt.set(request.systemPrompt());
                return Flux.just(new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, noopSkills);

            var command = new TurnCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
                "test-model", "Be very concise", List.of());

            StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            String prompt = capturedPrompt.get();
            assertThat(prompt).contains("## Environment");
            assertThat(prompt).contains("## Behavioral Rules");
            assertThat(prompt).contains("## Truthful Reporting");
            assertThat(prompt).contains("## Custom Instructions");
            assertThat(prompt).contains("Be very concise");
        }

        @Test
        void skillsAreAfterRuntimeContract() {
            SkillContextService skillService = new SkillContextService(
                new SkillRegistry() {
                    @Override public Flux<SkillDefinition> list() { return Flux.empty(); }
                    @Override public Mono<SkillContent> read(String id) {
                        return Mono.just(new SkillContent(id, id, "Skill body"));
                    }
                });

            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            ModelClient client = request -> {
                capturedPrompt.set(request.systemPrompt());
                return Flux.just(new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, skillService);

            var command = new TurnCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
                "test-model", "custom", List.of("my-skill"));

            StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            String prompt = capturedPrompt.get();
            int rulesIndex = prompt.indexOf("## Behavioral Rules");
            int skillsIndex = prompt.indexOf("## Active Skills");
            assertThat(rulesIndex).isGreaterThan(0);
            assertThat(skillsIndex).isGreaterThan(rulesIndex);
        }

        @Test
        void nullSkillsOmitActiveSkillsSection() {
            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            ModelClient client = request -> {
                capturedPrompt.set(request.systemPrompt());
                return Flux.just(new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, noopSkills);

            var command = new TurnCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
                "test-model", "base", null);

            StepVerifier.create(orchestrator.runTurn(command))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            assertThat(capturedPrompt.get()).doesNotContain("## Active Skills");
        }

        @Test
        void promptContainsWorkspaceRootsFromGuard() {
            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            ModelClient client = request -> {
                capturedPrompt.set(request.systemPrompt());
                return Flux.just(new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                client, noopExecutor, noopRegistry, transcriptStore, noopAudit, noopMetrics, noopSkills);

            StepVerifier.create(orchestrator.runTurn(turnCommand()))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            String prompt = capturedPrompt.get();
            for (var root : WorkspacePathGuard.effectiveAllowedRoots()) {
                assertThat(prompt).contains(root.toString());
            }
        }
    }

    @Nested
    class AllowlistPromptAndTools {

        @Test
        void filteredToolsMatchPromptCapabilityGuards() {
            AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();

            ToolRegistry registry = new ToolRegistry() {
                @Override public Optional<Tool> findByName(String name) { return Optional.empty(); }
                @Override public Set<String> listNames() { return Set.of("file_read", "file_write", "powershell"); }
                @Override public List<ToolDefinition> definitions() {
                    return List.of(
                        new ToolDefinition("file_read", "Read", Map.of()),
                        new ToolDefinition("file_write", "Write", Map.of()),
                        new ToolDefinition("powershell", "Shell", Map.of())
                    );
                }
            };

            ModelClient client = request -> {
                capturedRequest.set(request);
                return Flux.just(new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                client, noopExecutor, registry, transcriptStore, noopAudit, noopMetrics, noopSkills,
                new ToolPermissionProperties(true, ToolPermissionProperties.Mode.ALLOWLIST,
                    Set.of("file_read")),
                new com.clawcode.agent.core.prompt.SystemPromptBuilder(), 10);

            StepVerifier.create(orchestrator.runTurn(turnCommand()))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            ModelRequest req = capturedRequest.get();

            assertThat(req.tools()).hasSize(1);
            assertThat(req.tools().getFirst().name()).isEqualTo("file_read");

            String prompt = req.systemPrompt();
            assertThat(prompt).contains("## Available Tools");
            assertThat(prompt).contains("file_read");
            assertThat(prompt).doesNotContain("file_write");
            assertThat(prompt).doesNotContain("powershell");
            assertThat(prompt).contains("## Capability Restrictions");
            assertThat(prompt).contains("You cannot edit, create, or delete files");
            assertThat(prompt).contains("You cannot run commands, builds, tests, or git operations");
        }

        @Test
        void fullAllowlistHasNoCapabilityRestrictions() {
            AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();

            ToolRegistry registry = new ToolRegistry() {
                @Override public Optional<Tool> findByName(String name) { return Optional.empty(); }
                @Override public Set<String> listNames() { return Set.of("file_read", "file_write", "powershell"); }
                @Override public List<ToolDefinition> definitions() {
                    return List.of(
                        new ToolDefinition("file_read", "Read", Map.of()),
                        new ToolDefinition("file_write", "Write", Map.of()),
                        new ToolDefinition("powershell", "Shell", Map.of())
                    );
                }
            };

            ModelClient client = request -> {
                capturedRequest.set(request);
                return Flux.just(new ModelCompletedEvent());
            };

            var orchestrator = new DefaultQueryOrchestrator(
                client, noopExecutor, registry, transcriptStore, noopAudit, noopMetrics, noopSkills,
                new ToolPermissionProperties(false, ToolPermissionProperties.Mode.ALLOWLIST, null),
                new com.clawcode.agent.core.prompt.SystemPromptBuilder(), 10);

            StepVerifier.create(orchestrator.runTurn(turnCommand()))
            .expectNextMatches(e -> e instanceof QueryResultEvent)
                .expectNextMatches(e -> e instanceof QueryCompletedEvent)
                .verifyComplete();

            ModelRequest req = capturedRequest.get();

            assertThat(req.tools()).hasSize(3);
            assertThat(req.systemPrompt()).doesNotContain("## Capability Restrictions");
        }
    }
}
