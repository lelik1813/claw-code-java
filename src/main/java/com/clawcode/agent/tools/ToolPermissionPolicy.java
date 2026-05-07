package com.clawcode.agent.tools;

import reactor.core.publisher.Mono;

public interface ToolPermissionPolicy {

    Mono<ToolPermissionDecision> decide(ToolUseRequest request, ToolExecutionContext context);
}
