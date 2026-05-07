package com.clawcode.agent.tools.hooks;

import com.clawcode.agent.tools.ToolExecutionContext;
import com.clawcode.agent.tools.ToolResult;
import com.clawcode.agent.tools.ToolUseRequest;
import reactor.core.publisher.Mono;

/**
 * Extension point around tool execution. Implementations are discovered
 * as Spring beans and invoked in ordered sequence by the executor.
 *
 * <p>Correlation identifiers are available through the existing parameters:
 * <ul>
 *   <li>{@code request.toolCallId()} and {@code request.toolName()} identify the specific tool call</li>
 *   <li>{@code context.sessionId()} and {@code context.turnId()} correlate to the orchestration turn</li>
 * </ul>
 *
 * <p>Before-hooks run after permission check but before tool invocation. A before-hook error
 * prevents execution and suppresses after-hooks. After-hooks receive the {@link ToolResult};
 * an after-hook error is converted to {@link ToolResult#error}, replacing the original result.
 */
public interface ToolExecutionHook {

    default Mono<ToolPreHookResult> preTool(ToolPreHookContext context) {
        return beforeExecute(context.request(), context.executionContext())
            .thenReturn(ToolPreHookResult.continueWith(context.request(), context.messages()));
    }

    default Mono<ToolPostHookResult> postTool(ToolPostHookContext context) {
        return afterExecute(context.request(), context.executionContext(), context.result())
            .thenReturn(ToolPostHookResult.continueWith(context.result(), context.messages()));
    }

    default Mono<ToolPermissionDeniedHookResult> permissionDenied(ToolPermissionDeniedHookContext context) {
        return Mono.just(ToolPermissionDeniedHookResult.continueDefault());
    }

    default Mono<ToolStopHookResult> stop(ToolStopHookContext context) {
        return Mono.just(ToolStopHookResult.continueDefault());
    }

    default Mono<Void> beforeExecute(ToolUseRequest request, ToolExecutionContext context) {
        return Mono.empty();
    }

    default Mono<Void> afterExecute(ToolUseRequest request, ToolExecutionContext context, ToolResult result) {
        return Mono.empty();
    }
}
