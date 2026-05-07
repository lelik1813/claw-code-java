package com.clawcode.agent.tools;

import reactor.core.publisher.Mono;

public interface ToolExecutor {

    Mono<ToolResult> execute(ToolUseRequest request, ToolExecutionContext context);
}
