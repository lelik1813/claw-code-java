package com.clawcode.agent.tools;

import com.clawcode.agent.forensics.AuditTrail;
import com.clawcode.agent.forensics.ObservabilityMetrics;
import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.UserMessage;
import com.clawcode.agent.tools.hooks.ToolExecutionHook;
import com.clawcode.agent.tools.hooks.ToolHookPipeline;
import com.clawcode.agent.tools.hooks.ToolPermissionDeniedHookContext;
import com.clawcode.agent.tools.hooks.ToolPermissionDeniedHookResult;
import com.clawcode.agent.tools.hooks.ToolPostHookContext;
import com.clawcode.agent.tools.hooks.ToolPostHookResult;
import com.clawcode.agent.tools.hooks.ToolPreHookContext;
import com.clawcode.agent.tools.hooks.ToolPreHookResult;
import com.clawcode.agent.tools.hooks.ToolStopHookContext;
import com.clawcode.agent.tools.hooks.ToolStopHookResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class ToolHookPipelineTest {

    private final AuditTrail noopAudit = event -> Mono.empty();
    private final ObservabilityMetrics metrics = new ObservabilityMetrics(new SimpleMeterRegistry());
    private final ToolPermissionPolicy allowAll = (req, ctx) ->
        Mono.just(new ToolPermissionDecision.Allow());
    private final ToolExecutionContext ctx = new ToolExecutionContext("t1", "s1", "m1", "system");

    @Test
    void multipleHooksCalledInRegistrationOrder() {
        List<String> order = new ArrayList<>();
        Tool echo = new StubTool("echo", Mono.just("done"));

        ToolExecutionHook hookA = recordingHook("A", order);
        ToolExecutionHook hookB = recordingHook("B", order);

        ToolExecutor executor = new DefaultToolExecutor(
            registryOf(echo), allowAll, noopAudit, metrics, List.of(hookA, hookB));

        StepVerifier.create(executor.execute(new ToolUseRequest("c1", "echo", "hi"), ctx))
            .assertNext(r -> assertThat(r.isError()).isFalse())
            .verifyComplete();

        assertThat(order).containsExactly("A.before", "B.before", "A.after", "B.after");
    }

    @Test
    void asyncHooksExecuteInRegistrationOrder() {
        List<String> order = new ArrayList<>();
        Tool echo = new StubTool("echo", Mono.just("done"));

        ToolExecutionHook hookA = new ToolExecutionHook() {
            @Override public Mono<Void> beforeExecute(ToolUseRequest req, ToolExecutionContext ctx) {
                return Mono.delay(Duration.ofMillis(50)).doOnNext(i -> order.add("A.before")).then();
            }
            @Override public Mono<Void> afterExecute(ToolUseRequest req, ToolExecutionContext ctx, ToolResult result) {
                return Mono.delay(Duration.ofMillis(50)).doOnNext(i -> order.add("A.after")).then();
            }
        };
        ToolExecutionHook hookB = new ToolExecutionHook() {
            @Override public Mono<Void> beforeExecute(ToolUseRequest req, ToolExecutionContext ctx) {
                return Mono.delay(Duration.ofMillis(10)).doOnNext(i -> order.add("B.before")).then();
            }
            @Override public Mono<Void> afterExecute(ToolUseRequest req, ToolExecutionContext ctx, ToolResult result) {
                return Mono.delay(Duration.ofMillis(10)).doOnNext(i -> order.add("B.after")).then();
            }
        };

        ToolExecutor executor = new DefaultToolExecutor(
            registryOf(echo), allowAll, noopAudit, metrics, List.of(hookA, hookB));

        StepVerifier.create(executor.execute(new ToolUseRequest("c1", "echo", "hi"), ctx))
            .assertNext(r -> assertThat(r.isError()).isFalse())
            .verifyComplete();

        assertThat(order).containsExactly("A.before", "B.before", "A.after", "B.after");
    }

    @Test
    void pipelineResumesAfterSuccessfulHooks() {
        List<String> order = new ArrayList<>();
        Tool echo = new StubTool("echo", Mono.just("result"));

        ToolExecutionHook hookA = new ToolExecutionHook() {
            @Override public Mono<Void> beforeExecute(ToolUseRequest req, ToolExecutionContext ctx) {
                order.add("A.before");
                return Mono.empty();
            }
            @Override public Mono<Void> afterExecute(ToolUseRequest req, ToolExecutionContext ctx, ToolResult result) {
                order.add("A.after:" + result.output());
                return Mono.empty();
            }
        };
        ToolExecutionHook hookB = new ToolExecutionHook() {
            @Override public Mono<Void> beforeExecute(ToolUseRequest req, ToolExecutionContext ctx) {
                order.add("B.before");
                return Mono.empty();
            }
            @Override public Mono<Void> afterExecute(ToolUseRequest req, ToolExecutionContext ctx, ToolResult result) {
                order.add("B.after:" + result.output());
                return Mono.empty();
            }
        };

        ToolExecutor executor = new DefaultToolExecutor(
            registryOf(echo), allowAll, noopAudit, metrics, List.of(hookA, hookB));

        StepVerifier.create(executor.execute(new ToolUseRequest("c1", "echo", "hi"), ctx))
            .assertNext(r -> {
                assertThat(r.isError()).isFalse();
                assertThat(r.output()).isEqualTo("result");
            })
            .verifyComplete();

        assertThat(order).containsExactly("A.before", "B.before", "A.after:result", "B.after:result");
    }

    @Test
    void firstBeforeHookErrorStopsPipeline() {
        List<String> order = new ArrayList<>();
        Tool echo = new StubTool("echo", Mono.just("should not run"));

        ToolExecutionHook hookA = new ToolExecutionHook() {
            @Override public Mono<Void> beforeExecute(ToolUseRequest req, ToolExecutionContext ctx) {
                order.add("A.before");
                return Mono.error(new RuntimeException("A-blocked"));
            }
            @Override public Mono<Void> afterExecute(ToolUseRequest req, ToolExecutionContext ctx, ToolResult result) {
                order.add("A.after");
                return Mono.empty();
            }
        };
        ToolExecutionHook hookB = recordingHook("B", order);

        ToolExecutor executor = new DefaultToolExecutor(
            registryOf(echo), allowAll, noopAudit, metrics, List.of(hookA, hookB));

        StepVerifier.create(executor.execute(new ToolUseRequest("c1", "echo", "hi"), ctx))
            .assertNext(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.errorMessage()).isEqualTo("A-blocked");
            })
            .verifyComplete();

        assertThat(order).containsExactly("A.before");
    }

    @Test
    void toolErrorStillRunsPostHooks() {
        List<String> order = new ArrayList<>();
        Tool failing = new StubTool("echo", Mono.error(new RuntimeException("tool-boom")));

        ToolExecutionHook hookA = new ToolExecutionHook() {
            @Override public Mono<Void> beforeExecute(ToolUseRequest req, ToolExecutionContext ctx) {
                order.add("A.before");
                return Mono.empty();
            }
            @Override public Mono<Void> afterExecute(ToolUseRequest req, ToolExecutionContext ctx, ToolResult result) {
                order.add("A.after:" + result.isError() + ":" + result.errorMessage());
                return Mono.empty();
            }
        };

        ToolExecutor executor = new DefaultToolExecutor(
            registryOf(failing), allowAll, noopAudit, metrics, List.of(hookA));

        StepVerifier.create(executor.execute(new ToolUseRequest("c1", "echo", "hi"), ctx))
            .assertNext(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.errorMessage()).isEqualTo("tool-boom");
            })
            .verifyComplete();

        assertThat(order).containsExactly("A.before", "A.after:true:tool-boom");
    }

    @Test
    void noHooksBehavesIdenticallyToBeforeHookIntegration() {
        Tool echo = new StubTool("echo", Mono.just("ok"));
        ToolExecutor withNoHooks = new DefaultToolExecutor(
            registryOf(echo), allowAll, noopAudit, metrics, List.of());
        ToolExecutor withNoopHook = new DefaultToolExecutor(
            registryOf(echo), allowAll, noopAudit, metrics,
            List.of(new ToolExecutionHook() {
                @Override public Mono<Void> beforeExecute(ToolUseRequest req, ToolExecutionContext ctx) {
                    return Mono.empty();
                }
                @Override public Mono<Void> afterExecute(ToolUseRequest req, ToolExecutionContext ctx, ToolResult result) {
                    return Mono.empty();
                }
            }));

        ToolUseRequest request = new ToolUseRequest("c1", "echo", "hi");

        ToolResult noHooksResult = withNoHooks.execute(request, ctx).block();
        ToolResult noopHookResult = withNoopHook.execute(request, ctx).block();

        assertThat(noHooksResult.output()).isEqualTo(noopHookResult.output());
        assertThat(noHooksResult.isError()).isEqualTo(noopHookResult.isError());
        assertThat(noHooksResult.toolCallId()).isEqualTo(noopHookResult.toolCallId());
    }

    @Test
    void hookReceivesAllCorrelationIds() {
        Tool echo = new StubTool("echo", Mono.just("ok"));
        ToolExecutionContext correlationCtx = new ToolExecutionContext("turn-42", "sess-99", "model-x", "system");

        List<String> captured = new ArrayList<>();
        ToolExecutionHook capturingHook = new ToolExecutionHook() {
            @Override public Mono<Void> beforeExecute(ToolUseRequest req, ToolExecutionContext ctx) {
                captured.add("before:" + req.toolCallId() + ":" + req.toolName()
                    + ":" + ctx.sessionId() + ":" + ctx.turnId());
                return Mono.empty();
            }
            @Override public Mono<Void> afterExecute(ToolUseRequest req, ToolExecutionContext ctx, ToolResult result) {
                captured.add("after:" + req.toolCallId() + ":" + req.toolName()
                    + ":" + ctx.sessionId() + ":" + ctx.turnId());
                return Mono.empty();
            }
        };

        ToolExecutor executor = new DefaultToolExecutor(
            registryOf(echo), allowAll, noopAudit, metrics, List.of(capturingHook));

        StepVerifier.create(executor.execute(new ToolUseRequest("call-7", "echo", "hi"), correlationCtx))
            .assertNext(r -> assertThat(r.isError()).isFalse())
            .verifyComplete();

        assertThat(captured).containsExactly(
            "before:call-7:echo:sess-99:turn-42",
            "after:call-7:echo:sess-99:turn-42");
    }

    @Test
    void newLifecycleDefaultsInvokeLegacyCallbacks() {
        List<String> order = new ArrayList<>();
        ToolUseRequest request = new ToolUseRequest("c1", "echo", "hi");
        ToolResult result = ToolResult.success("c1", "echo", "ok");
        ToolExecutionHook legacyOnlyHook = new ToolExecutionHook() {
            @Override public Mono<Void> beforeExecute(ToolUseRequest req, ToolExecutionContext ctx) {
                order.add("before:" + req.toolCallId() + ":" + ctx.sessionId());
                return Mono.empty();
            }

            @Override public Mono<Void> afterExecute(ToolUseRequest req, ToolExecutionContext ctx, ToolResult result) {
                order.add("after:" + result.output());
                return Mono.empty();
            }
        };

        StepVerifier.create(legacyOnlyHook.preTool(new ToolPreHookContext(request, ctx, List.of())))
            .assertNext(pre -> {
                assertThat(pre.decision()).isEqualTo(ToolPreHookResult.Decision.CONTINUE);
                assertThat(pre.request()).isSameAs(request);
            })
            .verifyComplete();

        StepVerifier.create(legacyOnlyHook.postTool(new ToolPostHookContext(request, ctx, result, List.of())))
            .assertNext(post -> assertThat(post.result()).isSameAs(result))
            .verifyComplete();

        assertThat(order).containsExactly("before:c1:s1", "after:ok");
    }

    @Test
    void pipelinePreToolRunsInOrderAndChainsModifiedInput() {
        List<String> order = new ArrayList<>();
        ToolHookPipeline pipeline = new ToolHookPipeline(List.of(
            new ToolExecutionHook() {
                @Override public Mono<ToolPreHookResult> preTool(ToolPreHookContext context) {
                    order.add("A:" + context.request().input());
                    return Mono.just(ToolPreHookResult.continueWith(
                        new ToolUseRequest(context.request().toolCallId(), context.request().toolName(), "one"),
                        List.of(message("A"))));
                }
            },
            new ToolExecutionHook() {
                @Override public Mono<ToolPreHookResult> preTool(ToolPreHookContext context) {
                    order.add("B:" + context.request().input());
                    return Mono.just(ToolPreHookResult.continueWith(
                        new ToolUseRequest(context.request().toolCallId(), context.request().toolName(), "two"),
                        List.of(message("B"))));
                }
            }
        ));

        StepVerifier.create(pipeline.preTool(new ToolPreHookContext(
                new ToolUseRequest("c1", "echo", "zero"), ctx, List.of())))
            .assertNext(result -> {
                assertThat(result.decision()).isEqualTo(ToolPreHookResult.Decision.CONTINUE);
                assertThat(result.request().input()).isEqualTo("two");
                assertThat(result.messages()).hasSize(2);
            })
            .verifyComplete();

        assertThat(order).containsExactly("A:zero", "B:one");
    }

    @Test
    void pipelinePreToolStopsOnFirstDeny() {
        List<String> order = new ArrayList<>();
        ToolHookPipeline pipeline = new ToolHookPipeline(List.of(
            new ToolExecutionHook() {
                @Override public Mono<ToolPreHookResult> preTool(ToolPreHookContext context) {
                    order.add("A");
                    return Mono.just(ToolPreHookResult.deny("blocked", List.of(message("deny"))));
                }
            },
            new ToolExecutionHook() {
                @Override public Mono<ToolPreHookResult> preTool(ToolPreHookContext context) {
                    order.add("B");
                    return Mono.just(ToolPreHookResult.continueWith(context.request(), List.of()));
                }
            }
        ));

        StepVerifier.create(pipeline.preTool(new ToolPreHookContext(
                new ToolUseRequest("c1", "echo", "input"), ctx, List.of())))
            .assertNext(result -> {
                assertThat(result.decision()).isEqualTo(ToolPreHookResult.Decision.DENY);
                assertThat(result.denyReason()).isEqualTo("blocked");
                assertThat(result.messages()).hasSize(1);
            })
            .verifyComplete();

        assertThat(order).containsExactly("A");
    }

    @Test
    void pipelinePostToolAccumulatesContextMessages() {
        Message initial = message("initial");
        ToolHookPipeline pipeline = new ToolHookPipeline(List.of(
            new ToolExecutionHook() {
                @Override public Mono<ToolPostHookResult> postTool(ToolPostHookContext context) {
                    assertThat(context.messages()).containsExactly(initial);
                    return Mono.just(ToolPostHookResult.continueWith(context.result(), List.of(message("A"))));
                }
            },
            new ToolExecutionHook() {
                @Override public Mono<ToolPostHookResult> postTool(ToolPostHookContext context) {
                    assertThat(context.messages()).hasSize(2);
                    return Mono.just(ToolPostHookResult.continueWith(context.result(), List.of(message("B"))));
                }
            }
        ));

        StepVerifier.create(pipeline.postTool(new ToolPostHookContext(
                new ToolUseRequest("c1", "echo", "input"), ctx,
                ToolResult.success("c1", "echo", "ok"), List.of(initial))))
            .assertNext(result -> assertThat(result.messages()).hasSize(3))
            .verifyComplete();
    }

    @Test
    void pipelinePermissionDeniedUsesLastNonBlankReasonOverride() {
        ToolHookPipeline pipeline = new ToolHookPipeline(List.of(
            new ToolExecutionHook() {
                @Override public Mono<ToolPermissionDeniedHookResult> permissionDenied(
                    ToolPermissionDeniedHookContext context
                ) {
                    assertThat(context.reason()).isEqualTo("policy denied");
                    return Mono.just(ToolPermissionDeniedHookResult.overrideReason(
                        "first override", List.of(message("A"))));
                }
            },
            new ToolExecutionHook() {
                @Override public Mono<ToolPermissionDeniedHookResult> permissionDenied(
                    ToolPermissionDeniedHookContext context
                ) {
                    assertThat(context.reason()).isEqualTo("first override");
                    return Mono.just(ToolPermissionDeniedHookResult.continueWith(List.of(message("B"))));
                }
            },
            new ToolExecutionHook() {
                @Override public Mono<ToolPermissionDeniedHookResult> permissionDenied(
                    ToolPermissionDeniedHookContext context
                ) {
                    return Mono.just(ToolPermissionDeniedHookResult.overrideReason(
                        "last override", List.of(message("C"))));
                }
            }
        ));

        StepVerifier.create(pipeline.permissionDenied(new ToolPermissionDeniedHookContext(
                new ToolUseRequest("c1", "echo", "input"), ctx, "policy denied", List.of())))
            .assertNext(result -> {
                assertThat(result.overrideReason()).isEqualTo("last override");
                assertThat(result.messages()).hasSize(3);
            })
            .verifyComplete();
    }

    @Test
    void pipelineStopReturnsFirstRetryOrFailDecision() {
        List<String> order = new ArrayList<>();
        ToolHookPipeline pipeline = new ToolHookPipeline(List.of(
            new ToolExecutionHook() {
                @Override public Mono<ToolStopHookResult> stop(ToolStopHookContext context) {
                    order.add("A");
                    return Mono.just(ToolStopHookResult.continueDefault());
                }
            },
            new ToolExecutionHook() {
                @Override public Mono<ToolStopHookResult> stop(ToolStopHookContext context) {
                    order.add("B");
                    return Mono.just(ToolStopHookResult.retry(List.of(message("retry"))));
                }
            },
            new ToolExecutionHook() {
                @Override public Mono<ToolStopHookResult> stop(ToolStopHookContext context) {
                    order.add("C");
                    return Mono.just(ToolStopHookResult.fail("failed", "stop", List.of()));
                }
            }
        ));

        StepVerifier.create(pipeline.stop(new ToolStopHookContext("max_tokens", List.of(message("initial")))))
            .assertNext(result -> {
                assertThat(result.decision()).isEqualTo(ToolStopHookResult.Decision.RETRY);
                assertThat(result.messages()).hasSize(2);
            })
            .verifyComplete();

        assertThat(order).containsExactly("A", "B");
    }

    private static ToolExecutionHook recordingHook(String label, List<String> order) {
        return new ToolExecutionHook() {
            @Override public Mono<Void> beforeExecute(ToolUseRequest req, ToolExecutionContext ctx) {
                order.add(label + ".before");
                return Mono.empty();
            }
            @Override public Mono<Void> afterExecute(ToolUseRequest req, ToolExecutionContext ctx, ToolResult result) {
                order.add(label + ".after");
                return Mono.empty();
            }
        };
    }

    private static ToolRegistry registryOf(Tool tool) {
        return new ToolRegistry() {
            @Override public Optional<Tool> findByName(String name) { return Optional.of(tool); }
            @Override public Set<String> listNames() { return Set.of(tool.name()); }
        };
    }

    private static Message message(String content) {
        return new UserMessage(UUID.randomUUID(), Instant.now(), content);
    }

    private record StubTool(String name, Mono<Object> result) implements Tool {
        @Override public ToolDefinition definition() { return new ToolDefinition(name, "test tool", Map.of()); }
        @Override public Mono<Object> execute(Object input, Object context) { return result; }
    }
}
