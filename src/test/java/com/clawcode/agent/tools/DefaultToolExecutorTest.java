package com.clawcode.agent.tools;

import com.clawcode.agent.forensics.AuditEvent;
import com.clawcode.agent.forensics.AuditTrail;
import com.clawcode.agent.forensics.ObservabilityMetrics;
import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.UserMessage;
import com.clawcode.agent.tools.hooks.TestToolExecutionHook;
import com.clawcode.agent.tools.hooks.ToolExecutionHook;
import com.clawcode.agent.tools.hooks.ToolPermissionDeniedHookResult;
import com.clawcode.agent.tools.hooks.ToolPostHookResult;
import com.clawcode.agent.tools.hooks.ToolPreHookResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultToolExecutorTest {

    private static final com.clawcode.agent.forensics.AuditTrail noopAudit =
        event -> reactor.core.publisher.Mono.empty();

    private static final com.clawcode.agent.forensics.ObservabilityMetrics noopMetrics =
        new com.clawcode.agent.forensics.ObservabilityMetrics(
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

    private final ToolPermissionPolicy allowAll = (req, ctx) ->
        Mono.just(new ToolPermissionDecision.Allow());
    private final ToolExecutionContext ctx = new ToolExecutionContext("t1", "s1", "m1", "system");
    private final ToolUseRequest request = new ToolUseRequest("call-1", "echo", "hello");

    @Test
    void knownToolReturnsSuccessResult() {
        Tool echo = new StubTool("echo", Mono.just("hello back"));
        ToolExecutor executor = new DefaultToolExecutor(registryOf(echo), allowAll, noopAudit, noopMetrics, List.of());

        StepVerifier.create(executor.execute(request, ctx))
            .assertNext(r -> {
                assertThat(r.toolCallId()).isEqualTo("call-1");
                assertThat(r.toolName()).isEqualTo("echo");
                assertThat(r.output()).isEqualTo("hello back");
                assertThat(r.isError()).isFalse();
                assertThat(r.errorMessage()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void unknownToolReturnsErrorResult() {
        List<AuditEvent> capturedEvents = new ArrayList<>();
        AuditTrail recordingAudit = event -> {
            capturedEvents.add(event);
            return Mono.empty();
        };
        var meterRegistry = new SimpleMeterRegistry();
        var metrics = new ObservabilityMetrics(meterRegistry);
        ToolExecutor executor = new DefaultToolExecutor(emptyRegistry(), allowAll, recordingAudit, metrics, List.of());
        ToolUseRequest req = new ToolUseRequest("call-2", "missing", null);

        StepVerifier.create(executor.execute(req, ctx))
            .assertNext(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.errorMessage()).contains("Unknown tool: 'missing'");
                assertThat(r.errorMessage()).contains("Do not retry");
                assertThat(r.errorMessage()).contains("use an advertised tool");
                assertThat(r.toolCallId()).isEqualTo("call-2");
            })
            .verifyComplete();

        assertThat(capturedEvents).hasSize(1);
        assertThat(capturedEvents.get(0).eventType()).isEqualTo("tool.permission.unknown");
        assertThat(capturedEvents.get(0).attributes())
            .containsEntry("toolName", "missing")
            .containsEntry("toolCallId", "call-2")
            .containsKey("reason");

        assertThat(meterRegistry.find("agent.tool.unknown").counter().count()).isEqualTo(1);
    }

    @Test
    void toolExceptionReturnsErrorResult() {
        Tool failing = new StubTool("echo", Mono.error(new RuntimeException("kaboom")));
        ToolExecutor executor = new DefaultToolExecutor(registryOf(failing), allowAll, noopAudit, noopMetrics, List.of());

        StepVerifier.create(executor.execute(request, ctx))
            .assertNext(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.errorMessage()).isEqualTo("kaboom");
                assertThat(r.toolCallId()).isEqualTo("call-1");
            })
            .verifyComplete();
    }

    @Test
    void deniedToolReturnsErrorResultWithoutExecution() {
        List<AuditEvent> capturedEvents = new ArrayList<>();
        AuditTrail recordingAudit = event -> {
            capturedEvents.add(event);
            return Mono.empty();
        };
        var meterRegistry = new SimpleMeterRegistry();
        var metrics = new ObservabilityMetrics(meterRegistry);
        AtomicInteger executeCount = new AtomicInteger();
        Tool echo = new StubTool("echo", Mono.defer(() -> {
            executeCount.incrementAndGet();
            return Mono.just("should not run");
        }));
        ToolPermissionPolicy denyAll = (req, ctx) ->
            Mono.just(new ToolPermissionDecision.Deny("Blocked by policy"));
        ToolExecutor executor = new DefaultToolExecutor(registryOf(echo), denyAll, recordingAudit, metrics, List.of());

        StepVerifier.create(executor.execute(request, ctx))
            .assertNext(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.errorMessage()).contains("Tool 'echo' is denied");
                assertThat(r.errorMessage()).contains("Blocked by policy");
                assertThat(r.errorMessage()).contains("Do not retry");
                assertThat(r.errorMessage()).contains("use an advertised tool");
                assertThat(r.toolCallId()).isEqualTo("call-1");
            })
            .verifyComplete();

        assertThat(executeCount.get()).isZero();

        assertThat(capturedEvents).hasSize(1);
        assertThat(capturedEvents.get(0).eventType()).isEqualTo("tool.permission.denied");
        assertThat(capturedEvents.get(0).attributes())
            .containsEntry("toolName", "echo")
            .containsEntry("toolCallId", "call-1")
            .containsEntry("policyReason", "Blocked by policy")
            .containsKey("reason");

        assertThat(meterRegistry.find("agent.tool.denied").counter().count()).isEqualTo(1);
    }

    @Test
    void hooksCalledInCorrectOrderBeforeAndAfter() {
        List<String> order = new ArrayList<>();
        Tool echo = new StubTool("echo", Mono.just("result"));

        ToolExecutionHook hook = new ToolExecutionHook() {
            @Override public Mono<Void> beforeExecute(ToolUseRequest req, ToolExecutionContext ctx) {
                order.add("before");
                return Mono.empty();
            }
            @Override public Mono<Void> afterExecute(ToolUseRequest req, ToolExecutionContext ctx, ToolResult result) {
                order.add("after:" + result.isError());
                return Mono.empty();
            }
        };

        ToolExecutor executor = new DefaultToolExecutor(registryOf(echo), allowAll, noopAudit, noopMetrics, List.of(hook));

        StepVerifier.create(executor.execute(request, ctx))
            .assertNext(r -> assertThat(r.isError()).isFalse())
            .verifyComplete();

        assertThat(order).containsExactly("before", "after:false");
    }

    @Test
    void denyPathDoesNotInvokeToolOrAfterHook() {
        List<String> order = new ArrayList<>();
        AtomicInteger executeCount = new AtomicInteger();
        Tool echo = new StubTool("echo", Mono.defer(() -> {
            executeCount.incrementAndGet();
            return Mono.just("should not run");
        }));

        ToolExecutionHook hook = new ToolExecutionHook() {
            @Override public Mono<Void> beforeExecute(ToolUseRequest req, ToolExecutionContext ctx) {
                order.add("before");
                return Mono.empty();
            }
            @Override public Mono<Void> afterExecute(ToolUseRequest req, ToolExecutionContext ctx, ToolResult result) {
                order.add("after");
                return Mono.empty();
            }
        };

        List<AuditEvent> capturedEvents = new ArrayList<>();
        AuditTrail recordingAudit = event -> {
            capturedEvents.add(event);
            return Mono.empty();
        };

        ToolPermissionPolicy denyAll = (req, ctx) ->
            Mono.just(new ToolPermissionDecision.Deny("Denied by test policy"));
        ToolExecutor executor = new DefaultToolExecutor(registryOf(echo), denyAll, recordingAudit, noopMetrics, List.of(hook));

        StepVerifier.create(executor.execute(request, ctx))
            .assertNext(r -> {
                assertThat(r.errorMessage()).contains("Tool 'echo' is denied");
                assertThat(r.errorMessage()).contains("Denied by test policy");
                assertThat(r.errorMessage()).contains("Do not retry");
                assertThat(r.errorMessage()).contains("use an advertised tool");
            })
            .verifyComplete();

        assertThat(order).containsExactly("before");
        assertThat(executeCount.get()).isZero();

        assertThat(capturedEvents).hasSize(1);
        assertThat(capturedEvents.get(0).eventType()).isEqualTo("tool.permission.denied");
        assertThat(capturedEvents.get(0).attributes())
            .containsEntry("policyReason", "Denied by test policy")
            .satisfies(attrs -> {
                String reason = (String) attrs.get("reason");
                assertThat(reason).contains("Tool 'echo' is denied");
                assertThat(reason).contains("Denied by test policy");
                assertThat(reason).contains("Do not retry");
            });
    }

    @Test
    void preHookDeniesWithoutExecution() {
        AtomicInteger executeCount = new AtomicInteger();
        AtomicInteger policyCount = new AtomicInteger();
        Tool echo = new StubTool("echo", Mono.defer(() -> {
            executeCount.incrementAndGet();
            return Mono.just("should not run");
        }));
        ToolPermissionPolicy countingPolicy = (req, ctx) -> {
            policyCount.incrementAndGet();
            return Mono.just(new ToolPermissionDecision.Allow());
        };
        ToolExecutionHook denyingHook = TestToolExecutionHook.builder()
            .preTool(context -> Mono.just(ToolPreHookResult.deny("blocked before side effects", List.of())))
            .build();
        ToolExecutor executor = new DefaultToolExecutor(
            registryOf(echo), countingPolicy, noopAudit, noopMetrics, List.of(denyingHook));

        StepVerifier.create(executor.execute(request, ctx))
            .assertNext(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.errorMessage()).contains("Tool 'echo' is denied by hook");
                assertThat(r.errorMessage()).contains("blocked before side effects");
            })
            .verifyComplete();

        assertThat(executeCount.get()).isZero();
        assertThat(policyCount.get()).isZero();
    }

    @Test
    void preHookModifiesInputBeforeExecution() {
        AtomicReference<Object> policyInput = new AtomicReference<>();
        AtomicReference<Object> toolInput = new AtomicReference<>();
        Tool capturing = new Tool() {
            @Override public String name() { return "echo"; }
            @Override public ToolDefinition definition() {
                return new ToolDefinition("echo", "test", Map.of());
            }
            @Override public Mono<Object> execute(Object input, Object context) {
                toolInput.set(input);
                return Mono.just("ok:" + input);
            }
        };
        ToolPermissionPolicy capturingPolicy = (req, ctx) -> {
            policyInput.set(req.input());
            return Mono.just(new ToolPermissionDecision.Allow());
        };
        ToolExecutionHook modifyingHook = TestToolExecutionHook.builder()
            .preTool(context -> {
                ToolUseRequest modified = new ToolUseRequest(
                    context.request().toolCallId(),
                    context.request().toolName(),
                    "modified input");
                return Mono.just(ToolPreHookResult.continueWith(modified, List.of()));
            })
            .build();
        ToolExecutor executor = new DefaultToolExecutor(
            registryOf(capturing), capturingPolicy, noopAudit, noopMetrics, List.of(modifyingHook));

        StepVerifier.create(executor.execute(request, ctx))
            .assertNext(r -> {
                assertThat(r.isError()).isFalse();
                assertThat(r.output()).isEqualTo("ok:modified input");
            })
            .verifyComplete();

        assertThat(policyInput.get()).isEqualTo("modified input");
        assertThat(toolInput.get()).isEqualTo("modified input");
    }

    @Test
    void preHookModifiedInputPreservesToolIdentityInResultAndAudit() {
        List<AuditEvent> capturedEvents = new ArrayList<>();
        AuditTrail recordingAudit = event -> {
            capturedEvents.add(event);
            return Mono.empty();
        };
        AtomicReference<Object> toolInput = new AtomicReference<>();
        Tool capturing = new Tool() {
            @Override public String name() { return "echo"; }
            @Override public ToolDefinition definition() {
                return new ToolDefinition("echo", "test", Map.of());
            }
            @Override public Mono<Object> execute(Object input, Object context) {
                toolInput.set(input);
                return Mono.just("saw:" + input);
            }
        };
        ToolExecutionHook modifyingHook = TestToolExecutionHook.builder()
            .preTool(context -> Mono.just(ToolPreHookResult.continueWith(
                new ToolUseRequest(
                    context.request().toolCallId(),
                    context.request().toolName(),
                    "modified"),
                List.of())))
            .build();
        ToolExecutor executor = new DefaultToolExecutor(
            registryOf(capturing), allowAll, recordingAudit, noopMetrics, List.of(modifyingHook));

        StepVerifier.create(executor.execute(new ToolUseRequest("original-call", "echo", "original"), ctx))
            .assertNext(r -> {
                assertThat(r.isError()).isFalse();
                assertThat(r.toolCallId()).isEqualTo("original-call");
                assertThat(r.toolName()).isEqualTo("echo");
                assertThat(r.output()).isEqualTo("saw:modified");
            })
            .verifyComplete();

        assertThat(toolInput.get()).isEqualTo("modified");
        assertThat(capturedEvents)
            .extracting(AuditEvent::eventType)
            .contains("tool.permission.allowed", "tool.execution.success");
        assertThat(capturedEvents).allSatisfy(event -> {
            if (event.eventType().startsWith("tool.")) {
                assertThat(event.attributes())
                    .containsEntry("toolCallId", "original-call")
                    .containsEntry("toolName", "echo");
            }
        });
    }

    @Test
    void preHookDenialUsesNoRepeatGuidance() {
        Tool echo = new StubTool("echo", Mono.just("should not run"));
        ToolExecutionHook denyingHook = TestToolExecutionHook.builder()
            .preTool(context -> Mono.just(ToolPreHookResult.deny("unsafe input", List.of())))
            .build();
        ToolExecutor executor = new DefaultToolExecutor(
            registryOf(echo), allowAll, noopAudit, noopMetrics, List.of(denyingHook));

        StepVerifier.create(executor.execute(request, ctx))
            .assertNext(r -> {
                assertThat(r.errorMessage()).contains("Tool 'echo' is denied by hook: unsafe input");
                assertThat(r.errorMessage()).contains("Do not retry");
                assertThat(r.errorMessage()).contains("use an advertised tool");
            })
            .verifyComplete();
    }

    @Test
    void permissionDeniedHookAddsContext() {
        Message attachment = message("denied context");
        Tool echo = new StubTool("echo", Mono.just("should not run"));
        ToolPermissionPolicy denyAll = (req, ctx) ->
            Mono.just(new ToolPermissionDecision.Deny("policy blocked"));
        ToolExecutionHook hook = TestToolExecutionHook.builder()
            .permissionDenied(context -> Mono.just(
                ToolPermissionDeniedHookResult.continueWith(List.of(attachment))))
            .build();
        ToolExecutor executor = new DefaultToolExecutor(
            registryOf(echo), denyAll, noopAudit, noopMetrics, List.of(hook));

        StepVerifier.create(executor.execute(request, ctx))
            .assertNext(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.errorMessage()).contains("policy blocked");
                assertThat(r.contextMessages()).containsExactly(attachment);
            })
            .verifyComplete();
    }

    @Test
    void permissionDeniedHookOverridesReason() {
        Tool echo = new StubTool("echo", Mono.just("should not run"));
        ToolPermissionPolicy denyAll = (req, ctx) ->
            Mono.just(new ToolPermissionDecision.Deny("policy blocked"));
        ToolExecutionHook hook = TestToolExecutionHook.builder()
            .permissionDenied(context -> Mono.just(
                ToolPermissionDeniedHookResult.overrideReason("hook override", List.of())))
            .build();
        ToolExecutor executor = new DefaultToolExecutor(
            registryOf(echo), denyAll, noopAudit, noopMetrics, List.of(hook));

        StepVerifier.create(executor.execute(request, ctx))
            .assertNext(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.errorMessage()).contains("hook override");
                assertThat(r.errorMessage()).doesNotContain("policy blocked");
            })
            .verifyComplete();
    }

    @Test
    void permissionDeniedStillDoesNotExecuteTool() {
        AtomicInteger executeCount = new AtomicInteger();
        Tool echo = new StubTool("echo", Mono.defer(() -> {
            executeCount.incrementAndGet();
            return Mono.just("should not run");
        }));
        ToolPermissionPolicy denyAll = (req, ctx) ->
            Mono.just(new ToolPermissionDecision.Deny("policy blocked"));
        ToolExecutionHook hook = TestToolExecutionHook.builder()
            .permissionDenied(context -> Mono.just(ToolPermissionDeniedHookResult.overrideReason(
                "hook override", List.of(message("denied")))))
            .build();
        ToolExecutor executor = new DefaultToolExecutor(
            registryOf(echo), denyAll, noopAudit, noopMetrics, List.of(hook));

        StepVerifier.create(executor.execute(request, ctx))
            .assertNext(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.contextMessages()).hasSize(1);
            })
            .verifyComplete();

        assertThat(executeCount.get()).isZero();
    }

    @Test
    void toolReceivesContextWithSessionIdAndTurnId() {
        ToolExecutionContext[] capturedCtx = new ToolExecutionContext[1];
        Tool capturing = new Tool() {
            @Override public String name() { return "echo"; }
            @Override public ToolDefinition definition() {
                return new ToolDefinition("echo", "test", Map.of());
            }
            @Override public Mono<Object> execute(Object input, Object context) {
                capturedCtx[0] = (ToolExecutionContext) context;
                return Mono.just("captured");
            }
        };
        ToolExecutor executor = new DefaultToolExecutor(
            registryOf(capturing), allowAll, noopAudit, noopMetrics, List.of());

        StepVerifier.create(executor.execute(request, ctx))
            .expectNextCount(1)
            .verifyComplete();

        assertThat(capturedCtx[0]).isNotNull();
        assertThat(capturedCtx[0].sessionId()).isEqualTo("s1");
        assertThat(capturedCtx[0].turnId()).isEqualTo("t1");
    }

    @Test
    void beforeHookErrorBlocksToolExecution() {
        AtomicInteger executeCount = new AtomicInteger();
        Tool echo = new StubTool("echo", Mono.defer(() -> {
            executeCount.incrementAndGet();
            return Mono.just("should not run");
        }));

        ToolExecutionHook hook = new ToolExecutionHook() {
            @Override public Mono<Void> beforeExecute(ToolUseRequest req, ToolExecutionContext ctx) {
                return Mono.error(new RuntimeException("hook blocked"));
            }
            @Override public Mono<Void> afterExecute(ToolUseRequest req, ToolExecutionContext ctx, ToolResult result) {
                return Mono.empty();
            }
        };

        ToolExecutor executor = new DefaultToolExecutor(registryOf(echo), allowAll, noopAudit, noopMetrics, List.of(hook));

        StepVerifier.create(executor.execute(request, ctx))
            .assertNext(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.errorMessage()).isEqualTo("hook blocked");
            })
            .verifyComplete();

        assertThat(executeCount.get()).isZero();
    }

    @Test
    void afterHookErrorReplacesResultWithToolError() {
        Tool echo = new StubTool("echo", Mono.just("ok"));

        ToolExecutionHook hook = new ToolExecutionHook() {
            @Override public Mono<Void> beforeExecute(ToolUseRequest req, ToolExecutionContext ctx) {
                return Mono.empty();
            }
            @Override public Mono<Void> afterExecute(ToolUseRequest req, ToolExecutionContext ctx, ToolResult result) {
                return Mono.error(new RuntimeException("after-hook-failed"));
            }
        };

        ToolExecutor executor = new DefaultToolExecutor(registryOf(echo), allowAll, noopAudit, noopMetrics, List.of(hook));

        StepVerifier.create(executor.execute(request, ctx))
            .assertNext(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.errorMessage()).isEqualTo("after-hook-failed");
            })
            .verifyComplete();
    }

    @Test
    void postHookAddsContextMessage() {
        Message attachment = message("hook context");
        Tool echo = new StubTool("echo", Mono.just("ok"));
        ToolExecutionHook hook = TestToolExecutionHook.builder()
            .postTool(context -> Mono.just(
                ToolPostHookResult.continueWith(context.result(), List.of(attachment))))
            .build();
        ToolExecutor executor = new DefaultToolExecutor(
            registryOf(echo), allowAll, noopAudit, noopMetrics, List.of(hook));

        StepVerifier.create(executor.execute(request, ctx))
            .assertNext(r -> {
                assertThat(r.isError()).isFalse();
                assertThat(r.output()).isEqualTo("ok");
                assertThat(r.contextMessages()).containsExactly(attachment);
            })
            .verifyComplete();
    }

    @Test
    void postHookFailureStillReturnsErrorResult() {
        Tool echo = new StubTool("echo", Mono.just("ok"));
        ToolExecutionHook hook = TestToolExecutionHook.builder()
            .postTool(context -> Mono.error(new RuntimeException("post failed")))
            .build();
        ToolExecutor executor = new DefaultToolExecutor(
            registryOf(echo), allowAll, noopAudit, noopMetrics, List.of(hook));

        StepVerifier.create(executor.execute(request, ctx))
            .assertNext(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.errorMessage()).isEqualTo("post failed");
                assertThat(r.contextMessages()).isEmpty();
            })
            .verifyComplete();
    }

    private static ToolRegistry registryOf(Tool... tools) {
        return new ToolRegistry() {
            @Override
            public Optional<Tool> findByName(String name) {
                for (Tool t : tools) {
                    if (t.name().equals(name)) return Optional.of(t);
                }
                return Optional.empty();
            }

            @Override
            public Set<String> listNames() {
                return Set.of();
            }
        };
    }

    private static ToolRegistry emptyRegistry() {
        return new ToolRegistry() {
            @Override
            public Optional<Tool> findByName(String name) { return Optional.empty(); }
            @Override
            public Set<String> listNames() { return Set.of(); }
        };
    }

    private static Message message(String content) {
        return new UserMessage(UUID.randomUUID(), Instant.now(), content);
    }

    private record StubTool(String name, Mono<Object> result) implements Tool {
        @Override
        public ToolDefinition definition() { return new ToolDefinition(name, "test tool", Map.of()); }
        @Override
        public Mono<Object> execute(Object input, Object context) {
            return result;
        }
    }
}
