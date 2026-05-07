package com.clawcode.agent.core.query;

import com.clawcode.agent.config.AppProperties;
import com.clawcode.agent.core.prompt.RuntimePromptContext;
import com.clawcode.agent.core.prompt.SystemPromptBuilder;
import com.clawcode.agent.forensics.AuditEvent;
import com.clawcode.agent.forensics.AuditTrail;
import com.clawcode.agent.forensics.ObservabilityMetrics;
import com.clawcode.agent.model.*;
import com.clawcode.agent.persistence.TranscriptStore;
import com.clawcode.agent.skills.SkillContextService;
import com.clawcode.agent.tools.security.WorkspacePathGuard;
import io.micrometer.core.instrument.Timer;
import com.clawcode.agent.shared.message.AssistantContentBlock;
import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.AssistantTextBlock;
import com.clawcode.agent.shared.message.AssistantToolUseBlock;
import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.ToolResultMessage;
import com.clawcode.agent.shared.message.UserMessage;
import com.clawcode.agent.tools.ToolExecutionContext;
import com.clawcode.agent.tools.ToolExecutor;
import com.clawcode.agent.tools.ToolDefinition;
import com.clawcode.agent.tools.ToolErrorMessages;
import com.clawcode.agent.tools.ToolPermissionProperties;
import com.clawcode.agent.tools.ToolPermissionRules;
import com.clawcode.agent.tools.ToolRegistry;
import com.clawcode.agent.tools.ToolResult;
import com.clawcode.agent.tools.ToolUseRequest;
import com.clawcode.agent.tools.hooks.ToolHookPipeline;
import com.clawcode.agent.tools.hooks.ToolStopHookContext;
import com.clawcode.agent.tools.hooks.ToolStopHookResult;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public final class DefaultQueryOrchestrator implements QueryOrchestrator {

    private static final int DEFAULT_MAX_TOOL_ROUNDS = 10;
    private static final String MAX_OUTPUT_CONTINUATION_MESSAGE =
        "[continuation request]\n"
        + "The previous response stopped because max_output_tokens was reached. "
        + "Resume directly from the exact point where you stopped. "
        + "Do not restart or repeat completed text.";
    private static final String MAX_OUTPUT_LIMIT_NOTICE =
        "The response stopped because max_output_tokens was reached before completion. "
        + "Start a new turn to continue.";

    private final ModelClient modelClient;
    private final ToolExecutor toolExecutor;
    private final ToolRegistry toolRegistry;
    private final TranscriptStore transcriptStore;
    private final AuditTrail auditTrail;
    private final ObservabilityMetrics metrics;
    private final SkillContextService skillContextService;
    private final ToolPermissionProperties toolPermissionProperties;
    private final SystemPromptBuilder promptBuilder;
    private final int maxToolRounds;
    private final int maxToolConcurrency;
    private final ToolResultBudgeter toolResultBudgeter;
    private final ToolResultBudget toolResultBudget;
    private final int toolSummaryMinCalls;
    private final ContextBudgetGuard contextBudgetGuard;
    private final TranscriptCompactor transcriptCompactor;
    private final int maxModelRequestChars;
    private final boolean autoCompactEnabled;
    private final int compactPreserveRecentMessages;
    private final int maxOutputRecoveryAttempts;
    private final ToolHookPipeline hookPipeline;

    @Autowired
    public DefaultQueryOrchestrator(
        ModelClient modelClient,
        ToolExecutor toolExecutor,
        ToolRegistry toolRegistry,
        TranscriptStore transcriptStore,
        AuditTrail auditTrail,
        ObservabilityMetrics metrics,
        SkillContextService skillContextService,
        ToolPermissionProperties toolPermissionProperties,
        SystemPromptBuilder promptBuilder,
        QueryProperties queryProperties,
        AppProperties appProperties,
        ToolHookPipeline hookPipeline
    ) {
        this(modelClient, toolExecutor, toolRegistry, transcriptStore, auditTrail, metrics, skillContextService,
            toolPermissionProperties, promptBuilder, queryProperties.maxToolRounds(),
            appProperties.maxToolConcurrency(),
            new ToolResultBudget(appProperties.maxToolResultChars(), appProperties.toolResultExcerptChars()),
            appProperties.toolSummaryMinCalls(),
            queryProperties.maxModelRequestChars(),
            queryProperties.autoCompactEnabled(),
            queryProperties.compactPreserveRecentMessages(),
            queryProperties.maxOutputRecoveryAttempts(),
            hookPipeline);
    }

    DefaultQueryOrchestrator(
        ModelClient modelClient,
        ToolExecutor toolExecutor,
        ToolRegistry toolRegistry,
        TranscriptStore transcriptStore,
        AuditTrail auditTrail,
        ObservabilityMetrics metrics,
        SkillContextService skillContextService
    ) {
        this(modelClient, toolExecutor, toolRegistry, transcriptStore, auditTrail, metrics, skillContextService,
            new ToolPermissionProperties(false, ToolPermissionProperties.Mode.ALLOWLIST, null),
            new SystemPromptBuilder(), DEFAULT_MAX_TOOL_ROUNDS, 4, defaultToolResultBudget(), 4,
            240000, true, 12, 2);
    }

    DefaultQueryOrchestrator(
        ModelClient modelClient,
        ToolExecutor toolExecutor,
        ToolRegistry toolRegistry,
        TranscriptStore transcriptStore,
        AuditTrail auditTrail,
        ObservabilityMetrics metrics,
        SkillContextService skillContextService,
        int maxToolRounds
    ) {
        this(modelClient, toolExecutor, toolRegistry, transcriptStore, auditTrail, metrics, skillContextService,
            new ToolPermissionProperties(false, ToolPermissionProperties.Mode.ALLOWLIST, null),
            new SystemPromptBuilder(), maxToolRounds, 4, defaultToolResultBudget(), 4,
            240000, true, 12, 2);
    }

    // Backward-compatible constructor for existing tests that pass
    // ToolPermissionProperties + SystemPromptBuilder + maxToolRounds
    DefaultQueryOrchestrator(
        ModelClient modelClient,
        ToolExecutor toolExecutor,
        ToolRegistry toolRegistry,
        TranscriptStore transcriptStore,
        AuditTrail auditTrail,
        ObservabilityMetrics metrics,
        SkillContextService skillContextService,
        ToolPermissionProperties toolPermissionProperties,
        SystemPromptBuilder promptBuilder,
        int maxToolRounds
    ) {
        this(modelClient, toolExecutor, toolRegistry, transcriptStore, auditTrail, metrics, skillContextService,
            toolPermissionProperties, promptBuilder, maxToolRounds, 4, defaultToolResultBudget(), 4,
            240000, true, 12, 2);
    }

    DefaultQueryOrchestrator(
        ModelClient modelClient,
        ToolExecutor toolExecutor,
        ToolRegistry toolRegistry,
        TranscriptStore transcriptStore,
        AuditTrail auditTrail,
        ObservabilityMetrics metrics,
        SkillContextService skillContextService,
        ToolPermissionProperties toolPermissionProperties,
        SystemPromptBuilder promptBuilder,
        int maxToolRounds,
        int maxToolConcurrency
    ) {
        this(modelClient, toolExecutor, toolRegistry, transcriptStore, auditTrail, metrics, skillContextService,
            toolPermissionProperties, promptBuilder, maxToolRounds, maxToolConcurrency, defaultToolResultBudget(), 4,
            240000, true, 12, 2);
    }

    DefaultQueryOrchestrator(
        ModelClient modelClient,
        ToolExecutor toolExecutor,
        ToolRegistry toolRegistry,
        TranscriptStore transcriptStore,
        AuditTrail auditTrail,
        ObservabilityMetrics metrics,
        SkillContextService skillContextService,
        ToolPermissionProperties toolPermissionProperties,
        SystemPromptBuilder promptBuilder,
        int maxToolRounds,
        int maxToolConcurrency,
        ToolResultBudget toolResultBudget
    ) {
        this(modelClient, toolExecutor, toolRegistry, transcriptStore, auditTrail, metrics, skillContextService,
            toolPermissionProperties, promptBuilder, maxToolRounds, maxToolConcurrency, toolResultBudget, 4,
            240000, true, 12, 2);
    }

    DefaultQueryOrchestrator(
        ModelClient modelClient,
        ToolExecutor toolExecutor,
        ToolRegistry toolRegistry,
        TranscriptStore transcriptStore,
        AuditTrail auditTrail,
        ObservabilityMetrics metrics,
        SkillContextService skillContextService,
        ToolPermissionProperties toolPermissionProperties,
        SystemPromptBuilder promptBuilder,
        int maxToolRounds,
        int maxToolConcurrency,
        ToolResultBudget toolResultBudget,
        int toolSummaryMinCalls
    ) {
        this(modelClient, toolExecutor, toolRegistry, transcriptStore, auditTrail, metrics, skillContextService,
            toolPermissionProperties, promptBuilder, maxToolRounds, maxToolConcurrency, toolResultBudget,
            toolSummaryMinCalls, 240000, true, 12, 2);
    }

    DefaultQueryOrchestrator(
        ModelClient modelClient,
        ToolExecutor toolExecutor,
        ToolRegistry toolRegistry,
        TranscriptStore transcriptStore,
        AuditTrail auditTrail,
        ObservabilityMetrics metrics,
        SkillContextService skillContextService,
        ToolPermissionProperties toolPermissionProperties,
        SystemPromptBuilder promptBuilder,
        int maxToolRounds,
        int maxToolConcurrency,
        ToolResultBudget toolResultBudget,
        int toolSummaryMinCalls,
        int maxModelRequestChars,
        boolean autoCompactEnabled
    ) {
        this(modelClient, toolExecutor, toolRegistry, transcriptStore, auditTrail, metrics, skillContextService,
            toolPermissionProperties, promptBuilder, maxToolRounds, maxToolConcurrency, toolResultBudget,
            toolSummaryMinCalls, maxModelRequestChars, autoCompactEnabled, 12, 2);
    }

    DefaultQueryOrchestrator(
        ModelClient modelClient,
        ToolExecutor toolExecutor,
        ToolRegistry toolRegistry,
        TranscriptStore transcriptStore,
        AuditTrail auditTrail,
        ObservabilityMetrics metrics,
        SkillContextService skillContextService,
        ToolPermissionProperties toolPermissionProperties,
        SystemPromptBuilder promptBuilder,
        int maxToolRounds,
        int maxToolConcurrency,
        ToolResultBudget toolResultBudget,
        int toolSummaryMinCalls,
        int maxModelRequestChars,
        boolean autoCompactEnabled,
        int compactPreserveRecentMessages
    ) {
        this(modelClient, toolExecutor, toolRegistry, transcriptStore, auditTrail, metrics, skillContextService,
            toolPermissionProperties, promptBuilder, maxToolRounds, maxToolConcurrency, toolResultBudget,
            toolSummaryMinCalls, maxModelRequestChars, autoCompactEnabled, compactPreserveRecentMessages, 2);
    }

    DefaultQueryOrchestrator(
        ModelClient modelClient,
        ToolExecutor toolExecutor,
        ToolRegistry toolRegistry,
        TranscriptStore transcriptStore,
        AuditTrail auditTrail,
        ObservabilityMetrics metrics,
        SkillContextService skillContextService,
        ToolPermissionProperties toolPermissionProperties,
        SystemPromptBuilder promptBuilder,
        int maxToolRounds,
        int maxToolConcurrency,
        ToolResultBudget toolResultBudget,
        int toolSummaryMinCalls,
        int maxModelRequestChars,
        boolean autoCompactEnabled,
        int compactPreserveRecentMessages,
        int maxOutputRecoveryAttempts
    ) {
        this(modelClient, toolExecutor, toolRegistry, transcriptStore, auditTrail, metrics, skillContextService,
            toolPermissionProperties, promptBuilder, maxToolRounds, maxToolConcurrency, toolResultBudget,
            toolSummaryMinCalls, maxModelRequestChars, autoCompactEnabled, compactPreserveRecentMessages,
            maxOutputRecoveryAttempts, new ToolHookPipeline(List.of()));
    }

    DefaultQueryOrchestrator(
        ModelClient modelClient,
        ToolExecutor toolExecutor,
        ToolRegistry toolRegistry,
        TranscriptStore transcriptStore,
        AuditTrail auditTrail,
        ObservabilityMetrics metrics,
        SkillContextService skillContextService,
        ToolPermissionProperties toolPermissionProperties,
        SystemPromptBuilder promptBuilder,
        int maxToolRounds,
        int maxToolConcurrency,
        ToolResultBudget toolResultBudget,
        int toolSummaryMinCalls,
        int maxModelRequestChars,
        boolean autoCompactEnabled,
        int compactPreserveRecentMessages,
        int maxOutputRecoveryAttempts,
        ToolHookPipeline hookPipeline
    ) {
        this.modelClient = modelClient;
        this.toolExecutor = toolExecutor;
        this.toolRegistry = toolRegistry;
        this.transcriptStore = transcriptStore;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.skillContextService = skillContextService;
        this.toolPermissionProperties = toolPermissionProperties;
        this.promptBuilder = promptBuilder;
        this.maxToolRounds = maxToolRounds;
        this.maxToolConcurrency = maxToolConcurrency;
        this.toolResultBudgeter = new ToolResultBudgeter();
        this.toolResultBudget = toolResultBudget;
        this.toolSummaryMinCalls = toolSummaryMinCalls;
        this.contextBudgetGuard = new ContextBudgetGuard(new ModelRequestSizeEstimator());
        this.transcriptCompactor = new TranscriptCompactor();
        this.maxModelRequestChars = maxModelRequestChars;
        this.autoCompactEnabled = autoCompactEnabled;
        this.compactPreserveRecentMessages = compactPreserveRecentMessages;
        this.maxOutputRecoveryAttempts = maxOutputRecoveryAttempts;
        this.hookPipeline = hookPipeline;
    }

    private static ToolResultBudget defaultToolResultBudget() {
        return new ToolResultBudget(12000, 4000);
    }

    @Override
    public Flux<QueryEvent> runTurn(TurnCommand command) {
        Timer.Sample sample = metrics.startTurn();
        return skillContextService.loadSkillContext(command.skillIds())
            .flatMapMany(skillContext -> {
                List<ToolDefinition> advertised = advertisedToolDefinitions();
                RuntimePromptContext ctx = new RuntimePromptContext(
                    Instant.now(),
                    Path.of(System.getProperty("user.dir")).normalize().toAbsolutePath(),
                    WorkspacePathGuard.effectiveAllowedRoots(),
                    advertised
                );
                String systemPrompt = promptBuilder.build(command.systemPrompt(), skillContext, ctx);
                TurnState state = new TurnState(
                    command.turnId(),
                    command.sessionId(),
                    command.model(),
                    systemPrompt,
                    command.messages(),
                    command.persistFromIndex()
                );
                return runModelRound(state, 0, advertised);
            })
            .doOnComplete(() -> metrics.recordTurnLatency(sample))
            .doOnError(e -> metrics.recordTurnLatency(sample));
    }

    private static final String MAX_ROUNDS_MESSAGE =
        "I stopped because the maximum tool rounds limit was reached. "
        + "If you need a longer conversation, please start a new session "
        + "or try consolidating your requests.";

    private Flux<QueryEvent> runModelRound(TurnState state, int round, List<ToolDefinition> advertisedTools) {
        if (round >= maxToolRounds) {
            state.stopReason("max_tool_rounds");
            state.addMessage(new AssistantMessage(
                UUID.randomUUID(), Instant.now(),
                List.of(new AssistantTextBlock(MAX_ROUNDS_MESSAGE))));
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("round", round);
            attrs.put("stopReason", "TOO_MANY_TOOL_ROUNDS");
            appendHistoryCounts(attrs, state);
            return audit("turn.completed", state, attrs)
                .thenMany(Flux.just(
                    new QueryTextDeltaEvent(MAX_ROUNDS_MESSAGE),
                    new QueryStopReasonEvent("max_tool_rounds"),
                    new QueryTranscriptUpdateEvent(new TurnTranscriptUpdate(state.takeUnpersistedDelta())),
                    buildResultEvent(state, false, null),
                    new QueryCompletedEvent()));
        }

        state.recordRound();
        List<ModelToolDefinition> tools = advertisedTools.stream()
            .map(d -> new ModelToolDefinition(d.name(), d.description(), d.inputSchema()))
            .toList();
        ModelRequest request = new ModelRequest(
            state.turnId(), state.modelHistory(), state.model(), state.systemPrompt(), tools);
        ContextBudgetCheck budgetCheck = contextBudgetGuard.check(
            request.systemPrompt(), request.messages(), request.tools(), maxModelRequestChars);
        Map<String, Object> compactionAttrs = null;
        if (!budgetCheck.withinBudget()) {
            if (!autoCompactEnabled) {
                return contextTooLargeFailure(state);
            }
            compactionAttrs = compactModelHistory(state, tools, budgetCheck.estimatedChars());
            request = new ModelRequest(
                state.turnId(), state.modelHistory(), state.model(), state.systemPrompt(), tools);
            budgetCheck = contextBudgetGuard.check(
                request.systemPrompt(), request.messages(), request.tools(), maxModelRequestChars);
            if (!budgetCheck.withinBudget()) {
                return contextTooLargeFailure(state);
            }
        }

        Map<String, Object> reqAttrs = new LinkedHashMap<>();
        reqAttrs.put("model", state.model());
        reqAttrs.put("round", round);
        appendHistoryCounts(reqAttrs, state);

        Flux<Void> compactionAudit = compactionAttrs == null
            ? Flux.empty()
            : audit("model.context.compacted", state, compactionAttrs);

        return compactionAudit
            .thenMany(audit("model.request.sent", state, reqAttrs))
            .thenMany(modelClient.stream(request))
            .onErrorResume(error -> Flux.just(new ModelErrorEvent(
                modelErrorMessage(error), "model_error")))
            .takeUntil(this::isTerminalModelEvent)
            .collectList()
            .flatMapMany(events -> handleModelRound(events, state, round, advertisedTools));
    }

    private String modelErrorMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        int responseBodySeparator = message.indexOf(" — ");
        if (responseBodySeparator >= 0) {
            return message.substring(0, responseBodySeparator);
        }
        return message;
    }

    private boolean isTerminalModelEvent(ModelEvent event) {
        return event instanceof ModelCompletedEvent || event instanceof ModelErrorEvent;
    }

    private Map<String, Object> compactModelHistory(
        TurnState state,
        List<ModelToolDefinition> tools,
        int beforeChars
    ) {
        List<Message> compacted = transcriptCompactor.compact(
            state.modelHistory(),
            compactPreserveRecentMessages);
        state.replaceModelHistory(compacted);
        int afterChars = contextBudgetGuard.check(
            state.systemPrompt(),
            state.modelHistory(),
            tools,
            Integer.MAX_VALUE).estimatedChars();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("beforeChars", beforeChars);
        attrs.put("afterChars", afterChars);
        attrs.put("preservedMessages", Math.min(compactPreserveRecentMessages, Math.max(0, compacted.size() - 1)));
        return attrs;
    }

    private Flux<QueryEvent> contextTooLargeFailure(TurnState state) {
        state.stopReason("context_too_large");
        state.addMessage(new AssistantMessage(
            UUID.randomUUID(), Instant.now(),
            List.of(new AssistantTextBlock(ContextBudgetGuard.FAILURE_MESSAGE))));
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("stopReason", "context_too_large");
        appendHistoryCounts(attrs, state);
        return audit("turn.completed", state, attrs)
            .thenMany(Flux.just(
                new QueryTextDeltaEvent(ContextBudgetGuard.FAILURE_MESSAGE),
                new QueryStopReasonEvent("context_too_large"),
                new QueryTranscriptUpdateEvent(new TurnTranscriptUpdate(state.takeUnpersistedDelta())),
                buildResultEvent(state, false, "context_too_large"),
                new QueryCompletedEvent()));
    }

    private List<ToolDefinition> advertisedToolDefinitions() {
        return toolRegistry.definitions().stream()
            .filter(this::isToolAdvertised)
            .toList();
    }

    private boolean isToolAdvertised(ToolDefinition definition) {
        return ToolPermissionRules.isAllowed(toolPermissionProperties, definition.name());
    }

    private Flux<QueryEvent> handleModelRound(List<ModelEvent> events, TurnState state, int round,
                                                List<ToolDefinition> advertisedTools) {
        boolean hasToolUses = events.stream().anyMatch(ModelToolUseEvent.class::isInstance);
        if (!hasToolUses && hasMaxOutputStop(events)) {
            if (state.maxOutputRecoveryAttempts() < maxOutputRecoveryAttempts) {
                return recoverMaxOutput(events, state, round, advertisedTools);
            }
            return emitMaxOutputRecoveryExhausted(events, state, round);
        }
        List<ModelToolUseEvent> toolUses = new ArrayList<>();
        List<AssistantContentBlock> assistantBlocks = new ArrayList<>();
        List<Flux<QueryEvent>> outputSegments = new ArrayList<>();
        List<QueryEvent> bufferedEvents = new ArrayList<>();

        for (ModelEvent event : events) {
            if (event instanceof ModelToolUseEvent toolUse) {
                toolUses.add(toolUse);
                assistantBlocks.add(new AssistantToolUseBlock(
                    toolUse.toolCallId(), toolUse.toolName(), toolUse.input()));
                flushBufferedEvents(outputSegments, bufferedEvents);
                outputSegments.add(emitToolRequested(toolUse, state, round));
            } else if (event instanceof ModelTextDeltaEvent delta) {
                appendAssistantTextBlock(assistantBlocks, delta.text());
                if (!hasToolUses) {
                    bufferedEvents.add(toQueryEvent(event, state));
                }
            } else if (event instanceof ModelThinkingBlockEvent thinking) {
                assistantBlocks.add(thinking.block());
            } else if (event instanceof ModelCompletedEvent) {
                if (!hasToolUses) {
                    outputSegments.add(handleNoToolStop(
                        state, round, advertisedTools, event, assistantBlocks, bufferedEvents));
                    return Flux.concat(outputSegments);
                }
            } else if (event instanceof ModelErrorEvent err) {
                flushBufferedEvents(outputSegments, bufferedEvents);
                outputSegments.add(emitModelError(state, round, err));
                return Flux.concat(outputSegments);
            } else {
                bufferedEvents.add(toQueryEvent(event, state));
            }
        }
        flushBufferedEvents(outputSegments, bufferedEvents);

        if (toolUses.isEmpty()) {
            prependRecoveredPartialText(state, assistantBlocks, bufferedEvents);
            if (!assistantBlocks.isEmpty()) {
                state.addMessage(new AssistantMessage(
                    UUID.randomUUID(), Instant.now(), List.copyOf(assistantBlocks)));
            }
            return Flux.concat(outputSegments);
        }

        state.addMessage(new AssistantMessage(
            UUID.randomUUID(), Instant.now(), List.copyOf(assistantBlocks)));

        outputSegments.add(Flux.just(
            new QueryTranscriptUpdateEvent(new TurnTranscriptUpdate(state.takeUnpersistedDelta()))));

        var requests = toolUses.stream()
            .map(tu -> new ToolUseRequest(tu.toolCallId(), tu.toolName(), tu.input()))
            .toList();
        var plan = ToolBatchPlanner.plan(requests);
        List<ToolCallOutcome> batchOutcomes = new ArrayList<>();

        outputSegments.add(executePlan(plan, state, round, batchOutcomes)
            .concatWith(Flux.defer(() -> emitToolUseSummaryIfPresent(state, round, batchOutcomes)))
            .concatWith(Flux.defer(() -> runModelRound(state, round + 1, advertisedTools))));
        return Flux.concat(outputSegments);
    }

    private Flux<QueryEvent> handleNoToolStop(
        TurnState state,
        int round,
        List<ToolDefinition> advertisedTools,
        ModelEvent terminalEvent,
        List<AssistantContentBlock> assistantBlocks,
        List<QueryEvent> bufferedEvents
    ) {
        return hookPipeline.stop(new ToolStopHookContext(resolvedStopReason(state), List.of()))
            .flatMapMany(decision -> {
                if (decision.decision() == ToolStopHookResult.Decision.RETRY) {
                    if (state.stopHookRetries() >= maxToolRounds || round + 1 >= maxToolRounds) {
                        decision.messages().forEach(state::addMessage);
                        assistantBlocks.clear();
                        bufferedEvents.clear();
                        return emitStopHookRetryLimitFailure(state, round);
                    }
                    state.recordStopHookRetry();
                    decision.messages().forEach(state::addModelOnlyMessage);
                    assistantBlocks.clear();
                    bufferedEvents.clear();
                    return runModelRound(state, round + 1, advertisedTools);
                }
                if (decision.decision() == ToolStopHookResult.Decision.FAIL) {
                    decision.messages().forEach(state::addMessage);
                    assistantBlocks.clear();
                    bufferedEvents.clear();
                    return emitStopHookFailure(state, round, decision);
                }
                prependRecoveredPartialText(state, assistantBlocks, bufferedEvents);
                List<Flux<QueryEvent>> outputSegments = new ArrayList<>();
                flushBufferedEvents(outputSegments, bufferedEvents);
                if (!assistantBlocks.isEmpty()) {
                    state.addMessage(new AssistantMessage(
                        UUID.randomUUID(), Instant.now(), List.copyOf(assistantBlocks)));
                    assistantBlocks.clear();
                }
                outputSegments.add(emitTurnCompleted(state, round, terminalEvent));
                return Flux.concat(outputSegments);
            });
    }

    private Flux<QueryEvent> emitStopHookRetryLimitFailure(TurnState state, int round) {
        return emitStopHookFailure(state, round, ToolStopHookResult.fail(
            "Stop hook retry limit reached.", "hook_stop_retry_limit", List.of()));
    }

    private Flux<QueryEvent> emitStopHookFailure(
        TurnState state,
        int round,
        ToolStopHookResult decision
    ) {
        state.stopReason(decision.stopReason());
        state.addMessage(new AssistantMessage(
            UUID.randomUUID(),
            Instant.now(),
            List.of(new AssistantTextBlock(decision.message()))));

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("round", round);
        attrs.put("stopReason", decision.stopReason());
        appendHistoryCounts(attrs, state);
        return audit("turn.completed", state, attrs)
            .thenMany(Flux.just(
                new QueryTextDeltaEvent(decision.message()),
                new QueryStopReasonEvent(decision.stopReason()),
                new QueryTranscriptUpdateEvent(new TurnTranscriptUpdate(state.takeUnpersistedDelta())),
                buildResultEvent(state, false, decision.stopReason()),
                new QueryCompletedEvent()));
    }

    private String resolvedStopReason(TurnState state) {
        return state.stopReason() == null ? "end_turn" : state.stopReason();
    }

    private boolean hasMaxOutputStop(List<ModelEvent> events) {
        return events.stream().anyMatch(event -> event instanceof ModelStopReasonEvent stop
            && isMaxOutputStop(stop.stopReason()));
    }

    private boolean isMaxOutputStop(String stopReason) {
        return "max_tokens".equals(stopReason) || "max_output_tokens".equals(stopReason);
    }

    private Flux<QueryEvent> recoverMaxOutput(
        List<ModelEvent> events,
        TurnState state,
        int round,
        List<ToolDefinition> advertisedTools
    ) {
        String partialText = collectText(events);
        state.recordMaxOutputRecoveryAttempt();
        state.appendRecoveredPartialText(partialText);
        if (!partialText.isEmpty()) {
            state.addModelOnlyMessage(new AssistantMessage(
                UUID.randomUUID(),
                Instant.now(),
                List.of(new AssistantTextBlock(partialText))));
        }
        state.addModelOnlyMessage(new UserMessage(
            UUID.randomUUID(),
            Instant.now(),
            MAX_OUTPUT_CONTINUATION_MESSAGE));
        return runModelRound(state, round + 1, advertisedTools);
    }

    private Flux<QueryEvent> emitMaxOutputRecoveryExhausted(
        List<ModelEvent> events,
        TurnState state,
        int round
    ) {
        state.appendRecoveredPartialText(collectText(events));
        String partial = state.consumeRecoveredPartialText();
        String finalText = partial.isEmpty()
            ? MAX_OUTPUT_LIMIT_NOTICE
            : partial + "\n\n" + MAX_OUTPUT_LIMIT_NOTICE;
        state.stopReason("max_output_tokens");
        state.addMessage(new AssistantMessage(
            UUID.randomUUID(),
            Instant.now(),
            List.of(new AssistantTextBlock(finalText))));

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("round", round);
        attrs.put("stopReason", "max_output_tokens");
        attrs.put("recoveryAttempts", state.maxOutputRecoveryAttempts());
        appendHistoryCounts(attrs, state);
        return audit("turn.completed", state, attrs)
            .thenMany(Flux.just(
                new QueryTextDeltaEvent(finalText),
                new QueryStopReasonEvent("max_output_tokens"),
                new QueryTranscriptUpdateEvent(new TurnTranscriptUpdate(state.takeUnpersistedDelta())),
                buildResultEvent(state, false, "max_output_tokens"),
                new QueryCompletedEvent()));
    }

    private String collectText(List<ModelEvent> events) {
        StringBuilder text = new StringBuilder();
        for (ModelEvent event : events) {
            if (event instanceof ModelTextDeltaEvent delta && delta.text() != null) {
                text.append(delta.text());
            }
        }
        return text.toString();
    }

    private void prependRecoveredPartialText(
        TurnState state,
        List<AssistantContentBlock> assistantBlocks,
        List<QueryEvent> bufferedEvents
    ) {
        String recovered = state.consumeRecoveredPartialText();
        if (recovered.isEmpty()) {
            return;
        }
        assistantBlocks.add(0, new AssistantTextBlock(recovered));
        bufferedEvents.add(0, new QueryTextDeltaEvent(recovered));
    }

    private void appendAssistantTextBlock(List<AssistantContentBlock> assistantBlocks, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int lastIndex = assistantBlocks.size() - 1;
        if (lastIndex >= 0 && assistantBlocks.get(lastIndex) instanceof AssistantTextBlock lastText) {
            assistantBlocks.set(lastIndex, new AssistantTextBlock(lastText.text() + text));
            return;
        }
        assistantBlocks.add(new AssistantTextBlock(text));
    }

    private void flushBufferedEvents(List<Flux<QueryEvent>> outputSegments, List<QueryEvent> bufferedEvents) {
        if (bufferedEvents.isEmpty()) {
            return;
        }
        outputSegments.add(Flux.fromIterable(List.copyOf(bufferedEvents)));
        bufferedEvents.clear();
    }

    private Flux<QueryEvent> emitTurnCompleted(TurnState state, int round, ModelEvent event) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("round", round);
        appendHistoryCounts(attrs, state);
        List<Message> delta = state.takeUnpersistedDelta();
        List<QueryEvent> events = new ArrayList<>();
        if (!delta.isEmpty()) {
            events.add(new QueryTranscriptUpdateEvent(new TurnTranscriptUpdate(delta)));
        }
        events.add(buildResultEvent(state));
        events.add(toQueryEvent(event, state));
        return audit("turn.completed", state, attrs)
            .thenMany(Flux.fromIterable(events));
    }

    private Flux<QueryEvent> emitModelError(TurnState state, int round, ModelErrorEvent err) {
        metrics.recordModelError();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("error", err.message());
        if (err.providerCode() != null) {
            attrs.put("providerCode", err.providerCode());
        }
        attrs.put("round", round);
        List<Message> delta = state.takeUnpersistedDelta();
        List<QueryEvent> events = new ArrayList<>();
        if (!delta.isEmpty()) {
            events.add(new QueryTranscriptUpdateEvent(new TurnTranscriptUpdate(delta)));
        }
        events.add(toQueryEvent(err, state));
        events.add(buildResultEvent(state, false, err.providerCode() != null ? err.providerCode() : "model_error"));
        return audit("turn.errored", state, attrs)
            .thenMany(Flux.fromIterable(events));
    }

    private Flux<QueryEvent> emitToolRequested(ModelToolUseEvent toolUse, TurnState state, int round) {
        String toolCallId = toolUse.toolCallId();
        String toolName = toolUse.toolName();

        Map<String, Object> reqAttrs = new LinkedHashMap<>();
        reqAttrs.put("toolName", toolName);
        reqAttrs.put("toolCallId", toolCallId);
        reqAttrs.put("round", round);

        return audit("tool.requested", state, reqAttrs)
            .thenMany(Flux.just(new QueryToolCallRequestedEvent(toolCallId, toolName, toolUse.input())));
    }

    private Flux<QueryEvent> executeToolUse(ModelToolUseEvent toolUse, TurnState state, int round) {
        ToolUseRequest toolRequest = new ToolUseRequest(
            toolUse.toolCallId(), toolUse.toolName(), toolUse.input());
        ToolExecutionContext ctx = new ToolExecutionContext(
            state.turnId(), state.sessionId(), state.model(), state.systemPrompt());

        return toolExecutor.execute(toolRequest, ctx)
            .flatMapMany(toolResult -> handleToolResult(budgetToolResult(toolRequest, toolResult), state, round));
    }

    private Flux<QueryEvent> executePlan(
        ToolBatchPlan plan,
        TurnState state,
        int round,
        List<ToolCallOutcome> batchOutcomes
    ) {
        List<Flux<QueryEvent>> groupFluxes = new ArrayList<>();
        for (var group : plan.groups()) {
            if (group.mode() == ToolBatchPlan.Mode.SERIAL) {
                groupFluxes.add(executeSerialGroup(group.toolCalls(), state, round, batchOutcomes));
            } else {
                groupFluxes.add(executeParallelGroup(group.toolCalls(), state, round, batchOutcomes));
            }
        }
        return Flux.concat(groupFluxes);
    }

    private Flux<QueryEvent> executeSerialGroup(
        List<ToolUseRequest> requests,
        TurnState state,
        int round,
        List<ToolCallOutcome> batchOutcomes
    ) {
        return Flux.fromIterable(requests)
            .concatMap(req -> executeSingleRequest(req, state, round, batchOutcomes));
    }

    private Flux<QueryEvent> executeParallelGroup(
        List<ToolUseRequest> requests,
        TurnState state,
        int round,
        List<ToolCallOutcome> batchOutcomes
    ) {
        var ctx = new ToolExecutionContext(
            state.turnId(), state.sessionId(), state.model(), state.systemPrompt());
        return Flux.fromIterable(requests)
            .index()
            .flatMap(tuple -> {
                int idx = tuple.getT1().intValue();
                ToolUseRequest req = tuple.getT2();
                return toolExecutor.execute(req, ctx)
                    .map(result -> Map.entry(idx, budgetToolResult(req, result)));
            }, maxToolConcurrency)
            .collectSortedList(Map.Entry.comparingByKey())
            .flatMapMany(results -> {
                List<Flux<QueryEvent>> segments = new ArrayList<>();
                for (var entry : results) {
                    ToolCallOutcome outcome = entry.getValue();
                    batchOutcomes.add(outcome);
                    segments.add(handleToolResult(outcome, state, round));
                }
                return Flux.concat(segments);
            });
    }

    private Flux<QueryEvent> executeSingleRequest(
        ToolUseRequest request,
        TurnState state,
        int round,
        List<ToolCallOutcome> batchOutcomes
    ) {
        var ctx = new ToolExecutionContext(
            state.turnId(), state.sessionId(), state.model(), state.systemPrompt());
        return toolExecutor.execute(request, ctx)
            .flatMapMany(toolResult -> {
                ToolCallOutcome outcome = budgetToolResult(request, toolResult);
                batchOutcomes.add(outcome);
                return handleToolResult(outcome, state, round);
            });
    }

    private ToolCallOutcome budgetToolResult(ToolUseRequest request, ToolResult toolResult) {
        return new ToolCallOutcome(request, toolResult,
            toolResultBudgeter.budget(request, toolResult, toolResultBudget));
    }

    private Flux<QueryEvent> emitToolUseSummaryIfPresent(
        TurnState state,
        int round,
        List<ToolCallOutcome> batchOutcomes
    ) {
        return Flux.defer(() -> ToolUseBatchSummary.build(round, batchOutcomes, toolSummaryMinCalls)
            .map(summary -> {
                state.addMessage(new UserMessage(UUID.randomUUID(), Instant.now(), summary.summaryText()));
                return Flux.<QueryEvent>just(
                    new QueryToolUseSummaryEvent(
                        summary.round(),
                        summary.totalToolCalls(),
                        summary.compactedResults(),
                        summary.errorResults(),
                        summary.paths(),
                        summary.summaryText()),
                    new QueryTranscriptUpdateEvent(new TurnTranscriptUpdate(state.takeUnpersistedDelta())));
            })
            .orElseGet(Flux::empty));
    }

    private Flux<QueryEvent> handleToolResult(ToolCallOutcome outcome, TurnState state, int round) {
        ToolResult toolResult = outcome.rawResult();
        String toolCallId = toolResult.toolCallId();
        String toolName = toolResult.toolName();
        BudgetedToolResult budgeted = outcome.budgeted();

        if (toolResult.isError() && ToolErrorMessages.isPermissionDenial(toolResult.errorMessage())) {
            state.recordPermissionDenial();
        }

        Instant now = Instant.now();
        String summary = budgeted.content();

        state.addMessage(new ToolResultMessage(
            UUID.randomUUID(), now, toolCallId, toolName,
            summary, toolResult.isError()));
        toolResult.contextMessages().forEach(state::addMessage);

        Map<String, Object> resAttrs = new LinkedHashMap<>();
        resAttrs.put("toolName", toolName);
        resAttrs.put("toolCallId", toolCallId);
        resAttrs.put("isError", toolResult.isError());
        resAttrs.put("round", round);

        return audit("tool.result.received", state, resAttrs)
            .thenMany(Flux.just(
                new QueryToolResultEvent(toolCallId, toolName, toolResult.isError(), summary),
                new QueryTranscriptUpdateEvent(new TurnTranscriptUpdate(state.takeUnpersistedDelta()))));
    }

    private QueryEvent toQueryEvent(ModelEvent event, TurnState state) {
        if (event instanceof ModelStreamStartedEvent) {
            return new QueryStreamStartedEvent();
        }
        if (event instanceof ModelTextDeltaEvent delta) {
            return new QueryTextDeltaEvent(delta.text());
        }
        if (event instanceof ModelCompletedEvent) {
            return new QueryCompletedEvent();
        }
        if (event instanceof ModelErrorEvent err) {
            return new QueryErrorEvent(err.message(), err.providerCode(), "model");
        }
        if (event instanceof ModelStopReasonEvent stop) {
            state.stopReason(stop.stopReason());
            return new QueryStopReasonEvent(stop.stopReason());
        }
        if (event instanceof ModelUsageEvent usage) {
            state.recordUsage(usage.inputTokens(), usage.outputTokens());
            return new QueryUsageEvent(usage.inputTokens(), usage.outputTokens());
        }
        return new QueryErrorEvent(
            "Unmapped model event: " + event.getClass().getSimpleName(),
            "UNMAPPED_MODEL_EVENT", "orchestrator");
    }

    private QueryResultEvent buildResultEvent(TurnState state) {
        return buildResultEvent(state, true, null);
    }

    private QueryResultEvent buildResultEvent(TurnState state, boolean success, String stopReason) {
        QueryResultEvent.Usage usage = state.latestInputTokens() != null || state.latestOutputTokens() != null
            ? new QueryResultEvent.Usage(state.latestInputTokens(), state.latestOutputTokens())
            : null;
        String resolvedStopReason = stopReason != null ? stopReason : state.stopReason();
        if (resolvedStopReason == null) {
            resolvedStopReason = "end_turn";
        }
        return new QueryResultEvent(success, resolvedStopReason, usage,
            state.finishDurationMs(), state.roundCount(), state.permissionDenialCount());
    }

    private Flux<Void> audit(String eventType, TurnState state, Map<String, Object> attributes) {
        return auditTrail.emit(AuditEvent.of(eventType, state.sessionId(), state.turnId(), attributes))
            .flux();
    }

    private void appendHistoryCounts(Map<String, Object> attrs, TurnState state) {
        List<Message> history = state.modelHistory();
        long assistantCount = history.stream().filter(AssistantMessage.class::isInstance).count();
        long toolResultCount = history.stream().filter(ToolResultMessage.class::isInstance).count();
        long toolUseCount = history.stream()
            .filter(AssistantMessage.class::isInstance)
            .map(AssistantMessage.class::cast)
            .flatMapToLong(m -> m.content().stream()
                .filter(AssistantToolUseBlock.class::isInstance)
                .mapToLong(b -> 1))
            .sum();
        attrs.put("messageCount", history.size());
        attrs.put("assistantMessagesCount", assistantCount);
        attrs.put("toolUseCount", toolUseCount);
        attrs.put("toolResultsCount", toolResultCount);
    }

}
