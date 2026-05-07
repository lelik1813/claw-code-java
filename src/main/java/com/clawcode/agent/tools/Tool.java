package com.clawcode.agent.tools;

import reactor.core.publisher.Mono;

public interface Tool {
    String name();

    ToolDefinition definition();

    Mono<Object> execute(Object input, Object context);
}
