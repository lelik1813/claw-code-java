package com.clawcode.agent.tools.hooks;

import com.clawcode.agent.tools.ToolExecutionContext;
import com.clawcode.agent.tools.ToolResult;
import com.clawcode.agent.tools.ToolUseRequest;
import reactor.core.publisher.Mono;

public class NoopToolExecutionHook implements ToolExecutionHook {

    @Override
    public Mono<Void> beforeExecute(ToolUseRequest request, ToolExecutionContext context) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> afterExecute(ToolUseRequest request, ToolExecutionContext context, ToolResult result) {
        return Mono.empty();
    }
}
