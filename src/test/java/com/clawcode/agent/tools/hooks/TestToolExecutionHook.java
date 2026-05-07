package com.clawcode.agent.tools.hooks;

import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Mono;

public final class TestToolExecutionHook implements ToolExecutionHook {

    private final Function<ToolPreHookContext, Mono<ToolPreHookResult>> preTool;
    private final Function<ToolPostHookContext, Mono<ToolPostHookResult>> postTool;
    private final Function<ToolPermissionDeniedHookContext, Mono<ToolPermissionDeniedHookResult>> permissionDenied;
    private final Function<ToolStopHookContext, Mono<ToolStopHookResult>> stop;

    private TestToolExecutionHook(Builder builder) {
        this.preTool = builder.preTool;
        this.postTool = builder.postTool;
        this.permissionDenied = builder.permissionDenied;
        this.stop = builder.stop;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Mono<ToolPreHookResult> preTool(ToolPreHookContext context) {
        return preTool != null ? preTool.apply(context) : ToolExecutionHook.super.preTool(context);
    }

    @Override
    public Mono<ToolPostHookResult> postTool(ToolPostHookContext context) {
        return postTool != null ? postTool.apply(context) : ToolExecutionHook.super.postTool(context);
    }

    @Override
    public Mono<ToolPermissionDeniedHookResult> permissionDenied(ToolPermissionDeniedHookContext context) {
        return permissionDenied != null
            ? permissionDenied.apply(context)
            : ToolExecutionHook.super.permissionDenied(context);
    }

    @Override
    public Mono<ToolStopHookResult> stop(ToolStopHookContext context) {
        return stop != null ? stop.apply(context) : ToolExecutionHook.super.stop(context);
    }

    public static final class Builder {
        private Function<ToolPreHookContext, Mono<ToolPreHookResult>> preTool;
        private Function<ToolPostHookContext, Mono<ToolPostHookResult>> postTool;
        private Function<ToolPermissionDeniedHookContext, Mono<ToolPermissionDeniedHookResult>> permissionDenied;
        private Function<ToolStopHookContext, Mono<ToolStopHookResult>> stop;

        private Builder() {
        }

        public Builder preTool(Function<ToolPreHookContext, Mono<ToolPreHookResult>> preTool) {
            this.preTool = Objects.requireNonNull(preTool, "preTool must not be null");
            return this;
        }

        public Builder postTool(Function<ToolPostHookContext, Mono<ToolPostHookResult>> postTool) {
            this.postTool = Objects.requireNonNull(postTool, "postTool must not be null");
            return this;
        }

        public Builder permissionDenied(
            Function<ToolPermissionDeniedHookContext, Mono<ToolPermissionDeniedHookResult>> permissionDenied
        ) {
            this.permissionDenied = Objects.requireNonNull(
                permissionDenied, "permissionDenied must not be null");
            return this;
        }

        public Builder stop(Function<ToolStopHookContext, Mono<ToolStopHookResult>> stop) {
            this.stop = Objects.requireNonNull(stop, "stop must not be null");
            return this;
        }

        public TestToolExecutionHook build() {
            return new TestToolExecutionHook(this);
        }
    }
}
