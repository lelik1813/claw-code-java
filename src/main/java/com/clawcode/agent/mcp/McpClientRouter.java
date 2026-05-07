package com.clawcode.agent.mcp;

import java.net.URI;
import java.util.EnumMap;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class McpClientRouter implements McpClient {

    private final McpProperties properties;
    private final Map<McpTransportType, McpClient> transports;

    McpClientRouter(McpProperties properties) {
        this.properties = properties;
        Map<McpTransportType, McpClient> map = new EnumMap<>(McpTransportType.class);
        map.put(McpTransportType.HTTP, new HttpMcpClient(properties));
        map.put(McpTransportType.STDIO, new StdioMcpClient(properties));
        map.put(McpTransportType.SSE, new SseMcpClient(properties));
        this.transports = map;
    }

    McpClientRouter(McpProperties properties, Map<McpTransportType, McpClient> transports) {
        this.properties = properties;
        this.transports = transports;
    }

    void shutdown() {
        transports.values().forEach(client -> {
            if (client instanceof StdioMcpClient s) s.shutdown();
            else if (client instanceof SseMcpClient s) s.shutdown();
        });
    }

    @Override
    public Flux<McpResource> listResources(String serverName) {
        return resolve(serverName)
            .flatMapMany(client -> client.listResources(serverName));
    }

    @Override
    public Mono<McpResourceContent> readResource(String serverName, URI uri) {
        return resolve(serverName)
            .flatMap(client -> client.readResource(serverName, uri));
    }

    private Mono<McpClient> resolve(String serverName) {
        McpProperties.McpServerDefinition def = properties.servers().get(serverName);
        if (def == null) {
            return Mono.error(new McpServerNotFoundException(serverName));
        }
        if (!def.enabled()) {
            return Mono.error(new McpRemoteException(serverName, "server is disabled", null));
        }
        McpClient client = transports.get(def.type());
        if (client == null) {
            return Mono.error(new McpRemoteException(serverName,
                "Unsupported transport type: " + def.type(), null));
        }
        return Mono.just(client);
    }
}
