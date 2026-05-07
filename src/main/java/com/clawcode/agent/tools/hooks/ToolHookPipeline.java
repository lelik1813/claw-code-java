package com.clawcode.agent.tools.hooks;

import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.tools.ToolResult;
import com.clawcode.agent.tools.ToolUseRequest;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;

public class ToolHookPipeline {

    private final List<ToolExecutionHook> hooks;

    public ToolHookPipeline(List<ToolExecutionHook> hooks) {
        this.hooks = List.copyOf(hooks == null ? List.of() : hooks);
    }

    public Mono<ToolPreHookResult> preTool(ToolPreHookContext context) {
        return preTool(0, context.request(), context, new ArrayList<>(context.messages()));
    }

    public Mono<ToolPostHookResult> postTool(ToolPostHookContext context) {
        return postTool(0, context.result(), context, new ArrayList<>(context.messages()));
    }

    public Mono<ToolPermissionDeniedHookResult> permissionDenied(ToolPermissionDeniedHookContext context) {
        return permissionDenied(0, context.reason(), false, context, new ArrayList<>(context.messages()));
    }

    public Mono<ToolStopHookResult> stop(ToolStopHookContext context) {
        return stop(0, context.stopReason(), new ArrayList<>(context.messages()));
    }

    private Mono<ToolPreHookResult> preTool(
        int index,
        ToolUseRequest request,
        ToolPreHookContext context,
        List<Message> messages
    ) {
        if (index >= hooks.size()) {
            return Mono.just(ToolPreHookResult.continueWith(request, messages));
        }
        ToolPreHookContext nextContext =
            new ToolPreHookContext(request, context.executionContext(), messages);
        return hooks.get(index).preTool(nextContext)
            .flatMap(result -> {
                List<Message> nextMessages = mergeMessages(messages, result.messages());
                if (result.decision() == ToolPreHookResult.Decision.DENY) {
                    return Mono.just(ToolPreHookResult.deny(result.denyReason(), nextMessages));
                }
                return preTool(index + 1, result.request(), context, nextMessages);
            });
    }

    private Mono<ToolPostHookResult> postTool(
        int index,
        ToolResult result,
        ToolPostHookContext context,
        List<Message> messages
    ) {
        if (index >= hooks.size()) {
            return Mono.just(ToolPostHookResult.continueWith(result, messages));
        }
        ToolPostHookContext nextContext =
            new ToolPostHookContext(context.request(), context.executionContext(), result, messages);
        return hooks.get(index).postTool(nextContext)
            .flatMap(hookResult -> postTool(
                index + 1,
                hookResult.result(),
                context,
                mergeMessages(messages, hookResult.messages())));
    }

    private Mono<ToolPermissionDeniedHookResult> permissionDenied(
        int index,
        String reason,
        boolean overrideApplied,
        ToolPermissionDeniedHookContext context,
        List<Message> messages
    ) {
        if (index >= hooks.size()) {
            return Mono.just(overrideApplied
                ? ToolPermissionDeniedHookResult.overrideReason(reason, messages)
                : ToolPermissionDeniedHookResult.continueWith(messages));
        }
        ToolPermissionDeniedHookContext nextContext =
            new ToolPermissionDeniedHookContext(
                context.request(), context.executionContext(), reason, messages);
        return hooks.get(index).permissionDenied(nextContext)
            .flatMap(result -> {
                String override = result.overrideReason();
                boolean hasOverride = override != null && !override.isBlank();
                return permissionDenied(
                    index + 1,
                    hasOverride ? override : reason,
                    overrideApplied || hasOverride,
                    context,
                    mergeMessages(messages, result.messages()));
            });
    }

    private Mono<ToolStopHookResult> stop(int index, String stopReason, List<Message> messages) {
        if (index >= hooks.size()) {
            return Mono.just(ToolStopHookResult.continueDefault());
        }
        ToolStopHookContext nextContext = new ToolStopHookContext(stopReason, messages);
        return hooks.get(index).stop(nextContext)
            .flatMap(result -> {
                List<Message> nextMessages = mergeMessages(messages, result.messages());
                if (result.decision() == ToolStopHookResult.Decision.RETRY) {
                    return Mono.just(ToolStopHookResult.retry(nextMessages));
                }
                if (result.decision() == ToolStopHookResult.Decision.FAIL) {
                    return Mono.just(ToolStopHookResult.fail(
                        result.message(), result.stopReason(), nextMessages));
                }
                return stop(index + 1, stopReason, nextMessages);
            });
    }

    private static List<Message> mergeMessages(List<Message> existing, List<Message> hookMessages) {
        if (hookMessages == null || hookMessages.isEmpty()) {
            return List.copyOf(existing);
        }
        if (startsWith(hookMessages, existing)) {
            return List.copyOf(hookMessages);
        }
        List<Message> merged = new ArrayList<>(existing);
        merged.addAll(hookMessages);
        return merged;
    }

    private static boolean startsWith(List<Message> values, List<Message> prefix) {
        if (values.size() < prefix.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!values.get(i).equals(prefix.get(i))) {
                return false;
            }
        }
        return true;
    }
}
