package com.clawcode.agent.tools;

import com.clawcode.agent.forensics.AuditTrail;
import com.clawcode.agent.forensics.ObservabilityMetrics;
import com.clawcode.agent.tools.hooks.ToolExecutionHook;
import com.clawcode.agent.tools.hooks.ToolHookPipeline;
import com.clawcode.agent.tools.hooks.ToolPermissionDeniedHookContext;
import com.clawcode.agent.tools.hooks.ToolPostHookContext;
import com.clawcode.agent.tools.hooks.ToolPreHookContext;
import com.clawcode.agent.tools.hooks.ToolPreHookResult;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class DefaultToolExecutor implements ToolExecutor {

    private final ToolRegistry registry;
    private final ToolPermissionPolicy policy;
    private final AuditTrail auditTrail;
    private final ObservabilityMetrics metrics;
    private final ToolHookPipeline hookPipeline;

    @Autowired
    public DefaultToolExecutor(ToolRegistry registry, ToolPermissionPolicy policy,
                               AuditTrail auditTrail, ObservabilityMetrics metrics,
                               ToolHookPipeline hookPipeline) {
        this.registry = registry;
        this.policy = policy;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.hookPipeline = hookPipeline;
    }

    public DefaultToolExecutor(ToolRegistry registry, ToolPermissionPolicy policy,
                               AuditTrail auditTrail, ObservabilityMetrics metrics,
                               List<ToolExecutionHook> hooks) {
        this(registry, policy, auditTrail, metrics, new ToolHookPipeline(hooks));
    }

    @Override
    public Mono<ToolResult> execute(ToolUseRequest request, ToolExecutionContext context) {
        return registry.findByName(request.toolName())
            .map(tool -> checkAndInvoke(tool, request, context))
            .orElseGet(() -> {
                String reason = ToolErrorMessages.unknown(request.toolName());
                Map<String, Object> unknownAttrs = attrs(request);
                unknownAttrs.put("reason", reason);
                metrics.recordToolUnknown();
                return audit("tool.permission.unknown", context, unknownAttrs)
                    .then(Mono.just(ToolResult.error(
                        request.toolCallId(), request.toolName(), reason)));
            });

    }

    private Mono<ToolResult> checkAndInvoke(Tool tool, ToolUseRequest request, ToolExecutionContext context) {
        Instant start = Instant.now();
        return hookPipeline.preTool(new ToolPreHookContext(request, context, List.of()))
            .flatMap(preResult -> {
                if (preResult.decision() == ToolPreHookResult.Decision.DENY) {
                    metrics.recordToolDenied();
                    String modelMessage = ToolErrorMessages.deniedByHook(
                        request.toolName(), preResult.denyReason());
                    Map<String, Object> denyAttrs = attrs(request);
                    denyAttrs.put("reason", modelMessage);
                    denyAttrs.put("hookReason", preResult.denyReason());
                    return audit("tool.permission.denied", context, denyAttrs)
                        .then(Mono.just(ToolResult.error(
                            request.toolCallId(), request.toolName(), modelMessage)));
                }
                return checkPermissionAndInvoke(tool, preResult.request(), context, start);
            })
            .onErrorResume(e -> {
                metrics.recordToolError();
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                Map<String, Object> attrs = executionAttrs(request, start, true, msg);
                return audit("tool.execution.error", context, attrs)
                    .thenReturn(ToolResult.error(request.toolCallId(), request.toolName(), msg));
            });
    }

    private Mono<ToolResult> checkPermissionAndInvoke(
        Tool tool,
        ToolUseRequest request,
        ToolExecutionContext context,
        Instant start
    ) {
        return policy.decide(request, context)
            .flatMap(decision -> {
                if (decision instanceof ToolPermissionDecision.Allow) {
                    metrics.recordToolCall();
                    return audit("tool.permission.allowed", context, attrs(request))
                        .then(invoke(tool, request, context, start));
                }
                metrics.recordToolDenied();
                ToolPermissionDecision.Deny deny = (ToolPermissionDecision.Deny) decision;
                return hookPipeline.permissionDenied(new ToolPermissionDeniedHookContext(
                        request, context, deny.reason(), List.of()))
                    .flatMap(hookResult -> {
                        String finalReason = hookResult.overrideReason() != null
                            && !hookResult.overrideReason().isBlank()
                                ? hookResult.overrideReason()
                                : deny.reason();
                        String modelMessage = ToolErrorMessages.denied(request.toolName(), finalReason);
                        Map<String, Object> denyAttrs = attrs(request);
                        denyAttrs.put("reason", modelMessage);
                        denyAttrs.put("policyReason", deny.reason());
                        if (hookResult.overrideReason() != null && !hookResult.overrideReason().isBlank()) {
                            denyAttrs.put("hookReasonOverride", hookResult.overrideReason());
                        }
                        return audit("tool.permission.denied", context, denyAttrs)
                            .then(Mono.just(ToolResult.error(
                                request.toolCallId(), request.toolName(),
                                modelMessage, hookResult.messages())));
                    });
            });
    }

    private Mono<ToolResult> invoke(Tool tool, ToolUseRequest request, ToolExecutionContext context, Instant start) {
        return tool.execute(request.input(), context)
            .flatMap(output -> {
                ToolResult result = ToolResult.success(request.toolCallId(), request.toolName(), output);
                Map<String, Object> attrs = executionAttrs(request, start, false, null);
                return audit("tool.execution.success", context, attrs)
                    .then(applyPostHooks(request, context, result))
                    .onErrorResume(e -> toolExecutionError(request, context, start, e));
            })
            .onErrorResume(e -> toolExecutionError(request, context, start, e)
                .flatMap(errorResult -> applyPostHooks(request, context, errorResult)
                    .onErrorResume(postError -> toolExecutionError(request, context, start, postError))));
    }

    private Mono<ToolResult> applyPostHooks(
        ToolUseRequest request,
        ToolExecutionContext context,
        ToolResult result
    ) {
        return hookPipeline.postTool(new ToolPostHookContext(
                request, context, result, result.contextMessages()))
            .map(postResult -> withContextMessages(postResult.result(), postResult.messages()));
    }

    private ToolResult withContextMessages(ToolResult result, List<com.clawcode.agent.shared.message.Message> messages) {
        if (result.isError()) {
            return ToolResult.error(result.toolCallId(), result.toolName(), result.errorMessage(), messages);
        }
        return ToolResult.success(result.toolCallId(), result.toolName(), result.output(), messages);
    }

    private Mono<ToolResult> toolExecutionError(
        ToolUseRequest request,
        ToolExecutionContext context,
        Instant start,
        Throwable error
    ) {
        metrics.recordToolError();
        String msg = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        Map<String, Object> attrs = executionAttrs(request, start, true, msg);
        return audit("tool.execution.error", context, attrs)
            .thenReturn(ToolResult.error(request.toolCallId(), request.toolName(), msg));
    }

    private Map<String, Object> executionAttrs(ToolUseRequest request, Instant start,
                                                boolean error, String errorMessage) {
        Map<String, Object> attrs = attrs(request);
        attrs.put("durationMs", Duration.between(start, Instant.now()).toMillis());
        attrs.put("error", error);
        if (errorMessage != null) {
            attrs.put("errorMessage", errorMessage);
        }
        return attrs;
    }

    private Map<String, Object> attrs(ToolUseRequest request) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("toolName", request.toolName());
        attrs.put("toolCallId", request.toolCallId());
        return attrs;
    }

    private Mono<Void> audit(String eventType, ToolExecutionContext context, Map<String, Object> attributes) {
        return auditTrail.emit(com.clawcode.agent.forensics.AuditEvent.of(
            eventType, context.sessionId(), context.turnId(), attributes));
    }
}
