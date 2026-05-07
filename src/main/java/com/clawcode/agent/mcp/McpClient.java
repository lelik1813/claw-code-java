package com.clawcode.agent.mcp;

import java.net.URI;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface McpClient {

    Flux<McpResource> listResources(String serverName);

    Mono<McpResourceContent> readResource(String serverName, URI uri);
}
