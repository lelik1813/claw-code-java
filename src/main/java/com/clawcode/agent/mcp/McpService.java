package com.clawcode.agent.mcp;

import java.net.URI;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class McpService {

    private final McpProperties properties;
    private final McpClient client;

    public McpService(McpProperties properties, McpClient client) {
        this.properties = properties;
        this.client = client;
    }

    public Flux<McpResource> listResources(String serverName) {
        return guardEnabled(serverName)
            .flatMapMany(s -> client.listResources(s));
    }

    public Mono<McpResourceContent> readResource(String serverName, URI uri) {
        return guardEnabled(serverName)
            .flatMap(s -> client.readResource(s, uri));
    }

    private Mono<String> guardEnabled(String serverName) {
        if (!properties.enabled()) {
            return Mono.error(new McpDisabledException());
        }
        if (serverName == null || serverName.isBlank()) {
            return Mono.error(new IllegalArgumentException("serverName is required"));
        }
        if (!properties.servers().containsKey(serverName)) {
            return Mono.error(new McpServerNotFoundException(serverName));
        }
        return Mono.just(serverName);
    }
}
