package com.clawcode.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class HttpMcpClient implements McpClient {

    private final McpProperties properties;
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    HttpMcpClient(McpProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.builder().build();
    }

    @Override
    public Flux<McpResource> listResources(String serverName) {
        return Mono.fromCallable(() -> resolveServer(serverName))
            .flatMapMany(def -> webClient.get()
                .uri(def.baseUrl() + "/resources")
                .headers(headers -> applyHeaders(headers, def))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(def.readTimeoutMs()))
                .flatMapMany(body -> parseResourceList(serverName, body)))
            .onErrorMap(WebClientResponseException.class, e ->
                new McpRemoteException(serverName,
                    "HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e))
            .onErrorMap(TimeoutException.class, e ->
                new McpRemoteException(serverName, "request timed out", e))
            .onErrorMap(IOException.class, e ->
                new McpRemoteException(serverName, "I/O error: " + e.getMessage(), e));
    }

    @Override
    public Mono<McpResourceContent> readResource(String serverName, URI uri) {
        return Mono.fromCallable(() -> resolveServer(serverName))
            .flatMap(def -> webClient.post()
                .uri(def.baseUrl() + "/resources/read")
                .headers(headers -> applyHeaders(headers, def))
                .bodyValue(Map.of("uri", uri.toString()))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(def.readTimeoutMs()))
                .map(body -> parseResourceContent(serverName, body, uri)))
            .onErrorMap(WebClientResponseException.class, e ->
                new McpRemoteException(serverName,
                    "HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e))
            .onErrorMap(TimeoutException.class, e ->
                new McpRemoteException(serverName, "request timed out", e))
            .onErrorMap(IOException.class, e ->
                new McpRemoteException(serverName, "I/O error: " + e.getMessage(), e));
    }

    private McpProperties.McpServerDefinition resolveServer(String serverName) {
        McpProperties.McpServerDefinition def = properties.servers().get(serverName);
        if (def == null) {
            throw new McpServerNotFoundException(serverName);
        }
        if (!def.enabled()) {
            throw new McpRemoteException(serverName, "server is disabled", null);
        }
        if (def.baseUrl() == null || def.baseUrl().isBlank()) {
            throw new McpRemoteException(serverName,
                "HTTP server requires non-blank 'baseUrl' config", null);
        }
        return def;
    }

    private void applyHeaders(org.springframework.http.HttpHeaders headers,
                              McpProperties.McpServerDefinition def) {
        if (!def.authToken().isEmpty()) {
            headers.setBearerAuth(def.authToken());
        }
        def.headers().forEach(headers::add);
    }

    private Flux<McpResource> parseResourceList(String serverName, String body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode resources = root.path("resources");
            if (!resources.isArray()) {
                return Flux.empty();
            }
            return Flux.fromIterable(() -> resources.elements())
                .map(node -> new McpResource(
                    parseUri(node.path("uri").asText(""), serverName),
                    node.path("name").asText(""),
                    node.path("description").asText(""),
                    node.path("mimeType").asText(null)));
        } catch (McpRemoteException e) {
            return Flux.error(e);
        } catch (Exception e) {
            return Flux.error(new McpRemoteException(serverName,
                "failed to parse resource list: " + e.getMessage(), e));
        }
    }

    private McpResourceContent parseResourceContent(String serverName, String body, URI uri) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode contents = root.path("contents");
            JsonNode content = contents.isArray() ? contents.get(0) : root.path("content");
            return new McpResourceContent(
                parseUri(content.path("uri").asText(uri.toString()), serverName),
                content.path("mimeType").asText("text/plain"),
                content.path("text").asText(""));
        } catch (McpRemoteException e) {
            throw e;
        } catch (Exception e) {
            throw new McpRemoteException(serverName,
                "failed to parse resource content: " + e.getMessage(), e);
        }
    }

    private URI parseUri(String raw, String serverName) {
        try {
            return URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new McpRemoteException(serverName,
                "invalid URI in response: " + raw, e);
        }
    }

    void shutdown() {
        // Stateless client — nothing to clean up
    }
}
