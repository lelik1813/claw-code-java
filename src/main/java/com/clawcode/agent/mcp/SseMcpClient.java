package com.clawcode.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Sinks;

class SseMcpClient implements McpClient {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpProperties properties;
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, SseSession> sessions = new ConcurrentHashMap<>();

    SseMcpClient(McpProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.builder().build();
    }

    @Override
    public Flux<McpResource> listResources(String serverName) {
        return getOrCreateSession(serverName)
            .flatMap(session -> session.sendRequest(
                "resources/list", mapper.createObjectNode(), readTimeoutMs(serverName)))
            .flatMapMany(result -> parseResourceList(serverName, result))
            .onErrorMap(WebClientResponseException.class, e ->
                new McpRemoteException(serverName,
                    "HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e))
            .onErrorMap(WebClientRequestException.class, e ->
                new McpRemoteException(serverName, "I/O error: " + e.getMessage(), e))
            .onErrorMap(TimeoutException.class, e ->
                new McpRemoteException(serverName, "request timed out", e))
            .onErrorMap(IOException.class, e ->
                new McpRemoteException(serverName, "I/O error: " + e.getMessage(), e));
    }

    @Override
    public Mono<McpResourceContent> readResource(String serverName, URI uri) {
        return getOrCreateSession(serverName)
            .flatMap(session -> {
                var params = mapper.createObjectNode();
                params.put("uri", uri.toString());
                return session.sendRequest("resources/read", params, readTimeoutMs(serverName));
            })
            .map(result -> parseResourceContent(serverName, result, uri))
            .onErrorMap(WebClientResponseException.class, e ->
                new McpRemoteException(serverName,
                    "HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e))
            .onErrorMap(WebClientRequestException.class, e ->
                new McpRemoteException(serverName, "I/O error: " + e.getMessage(), e))
            .onErrorMap(TimeoutException.class, e ->
                new McpRemoteException(serverName, "request timed out", e))
            .onErrorMap(IOException.class, e ->
                new McpRemoteException(serverName, "I/O error: " + e.getMessage(), e));
    }

    private long readTimeoutMs(String serverName) {
        McpProperties.McpServerDefinition def = properties.servers().get(serverName);
        return def != null ? def.readTimeoutMs() : 30_000;
    }

    private Mono<SseSession> getOrCreateSession(String serverName) {
        return Mono.defer(() -> {
            SseSession existing = sessions.get(serverName);
            if (existing != null && existing.isActive()) {
                return Mono.just(existing);
            }
            McpProperties.McpServerDefinition def = resolveServer(serverName);
            return SseSession.connect(serverName, def, webClient, mapper)
                .doOnNext(session -> sessions.put(serverName, session));
        });
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
                "SSE server requires non-blank 'baseUrl' config", null);
        }
        return def;
    }

    private Flux<McpResource> parseResourceList(String serverName, JsonNode result) {
        JsonNode resources = result.path("resources");
        if (!resources.isArray()) {
            return Flux.empty();
        }
        return Flux.fromIterable(() -> resources.elements())
            .map(node -> new McpResource(
                parseUri(node.path("uri").asText(""), serverName),
                node.path("name").asText(""),
                node.path("description").asText(""),
                node.path("mimeType").asText(null)));
    }

    private McpResourceContent parseResourceContent(String serverName, JsonNode result, URI uri) {
        JsonNode contents = result.path("contents");
        JsonNode content = contents.isArray() ? contents.get(0) : result.path("content");
        return new McpResourceContent(
            parseUri(content.path("uri").asText(uri.toString()), serverName),
            content.path("mimeType").asText("text/plain"),
            content.path("text").asText(""));
    }

    private URI parseUri(String raw, String serverName) {
        try {
            return URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new McpRemoteException(serverName, "invalid URI in response: " + raw, e);
        }
    }

    void shutdown() {
        sessions.values().forEach(SseSession::close);
        sessions.clear();
    }

    // --- SSE session ---

    static class SseSession {

        private final String serverName;
        private final String messageEndpoint;
        private final Map<String, String> customHeaders;
        private final WebClient webClient;
        private final ObjectMapper mapper;
        private final long readTimeoutMs;
        private final AtomicInteger nextId = new AtomicInteger(0);
        private final Map<Integer, MonoSink<JsonNode>> pending = new ConcurrentHashMap<>();
        private final Disposable sseListener;
        private final Disposable rawConnection;
        private final AtomicBoolean closed;
        private volatile boolean active = true;

        private SseSession(String serverName, String messageEndpoint,
                           Map<String, String> customHeaders, WebClient webClient,
                           ObjectMapper mapper, long readTimeoutMs,
                           Flux<ServerSentEvent<String>> eventStream,
                           Disposable rawConnection, AtomicBoolean closed) {
            this.serverName = serverName;
            this.messageEndpoint = messageEndpoint;
            this.customHeaders = customHeaders;
            this.webClient = webClient;
            this.mapper = mapper;
            this.readTimeoutMs = readTimeoutMs;
            this.rawConnection = rawConnection;
            this.closed = closed;
            this.sseListener = eventStream.subscribe(this::handleEvent, this::handleStreamError);
        }

        static Mono<SseSession> connect(String serverName, McpProperties.McpServerDefinition def,
                                         WebClient webClient, ObjectMapper mapper) {
            String sseUrl = def.baseUrl();
            long connectTimeoutMs = def.connectTimeoutMs();
            long readTimeoutMs = def.readTimeoutMs();

            Sinks.Many<ServerSentEvent<String>> sink =
                Sinks.many().replay().limit(256);

            Flux<ServerSentEvent<String>> rawStream = webClient.get()
                .uri(sseUrl)
                .headers(h -> def.headers().forEach(h::add))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});

            AtomicBoolean closed = new AtomicBoolean(false);

            Disposable rawConnection = rawStream.subscribe(
                event -> sink.tryEmitNext(event),
                error -> { if (!closed.get()) sink.tryEmitError(error); },
                () -> sink.tryEmitComplete()
            );

            return sink.asFlux()
                .filter(e -> "endpoint".equals(e.event()))
                .next()
                .timeout(Duration.ofMillis(connectTimeoutMs))
                .map(endpointEvent -> {
                    String endpoint = resolveUrl(sseUrl, endpointEvent.data());
                    return new SseSession(serverName, endpoint, def.headers(),
                        webClient, mapper, readTimeoutMs,
                        sink.asFlux(), rawConnection, closed);
                })
                .flatMap(session -> session.initialize().thenReturn(session))
                .onErrorMap(TimeoutException.class, e ->
                    new McpRemoteException(serverName,
                        "timed out waiting for SSE endpoint event", e))
                .onErrorMap(WebClientResponseException.class, e ->
                    new McpRemoteException(serverName,
                        "SSE connect failed: HTTP " + e.getStatusCode(), e))
                .doOnError(e -> rawConnection.dispose());
        }

        private Mono<Void> initialize() {
            var params = mapper.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.putObject("capabilities");
            var clientInfo = params.putObject("clientInfo");
            clientInfo.put("name", "claw-code-java");
            clientInfo.put("version", "1.0.0");

            return sendRequest("initialize", params, readTimeoutMs)
                .then(Mono.fromRunnable(() -> sendNotification("notifications/initialized")));
        }

        Mono<JsonNode> sendRequest(String method, JsonNode params, long timeoutMs) {
            return Mono.<JsonNode>create(sink -> {
                int id = nextId.incrementAndGet();
                var request = mapper.createObjectNode();
                request.put("jsonrpc", "2.0");
                request.put("id", id);
                request.put("method", method);
                request.set("params", params);

                pending.put(id, sink);
                sink.onDispose(() -> pending.remove(id));

                String body;
                try {
                    body = mapper.writeValueAsString(request);
                } catch (Exception e) {
                    pending.remove(id);
                    sink.error(e);
                    return;
                }

                webClient.post()
                    .uri(messageEndpoint)
                    .headers(h -> customHeaders.forEach(h::add))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .subscribe(
                        r -> {},
                        e -> {
                            pending.remove(id);
                            sink.error(new IOException("POST failed: " + e.getMessage(), e));
                        }
                    );
            }).timeout(Duration.ofMillis(timeoutMs));
        }

        private void sendNotification(String method) {
            var notification = mapper.createObjectNode();
            notification.put("jsonrpc", "2.0");
            notification.put("method", method);
            try {
                String body = mapper.writeValueAsString(notification);
                webClient.post()
                    .uri(messageEndpoint)
                    .headers(h -> customHeaders.forEach(h::add))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .subscribe(r -> {}, e -> {});
            } catch (Exception ignored) {}
        }

        private void handleEvent(ServerSentEvent<String> sse) {
            String data = sse.data();
            if (data == null) return;
            try {
                JsonNode json = mapper.readTree(data);
                JsonNode idNode = json.path("id");
                if (!idNode.isNumber()) return;
                int id = idNode.asInt();
                MonoSink<JsonNode> sink = pending.remove(id);
                if (sink == null) return;

                JsonNode error = json.path("error");
                if (!error.isMissingNode() && !error.isNull()) {
                    int code = error.path("code").asInt(-1);
                    String msg = error.path("message").asText("unknown error");
                    sink.error(new IOException("JSON-RPC error " + code + ": " + msg));
                } else {
                    sink.success(json.path("result"));
                }
            } catch (Exception ignored) {}
        }

        private void handleStreamError(Throwable error) {
            if (!active) return;
            active = false;
            IOException ioError = new IOException("SSE stream error: " + error.getMessage(), error);
            pending.forEach((id, sink) -> sink.error(ioError));
            pending.clear();
        }

        boolean isActive() {
            return active;
        }

        void close() {
            closed.set(true);
            active = false;
            IOException error = new IOException("SSE session closed");
            pending.forEach((id, sink) -> sink.error(error));
            pending.clear();
            sseListener.dispose();
            rawConnection.dispose();
        }

        private static String resolveUrl(String baseUrl, String path) {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("Empty endpoint path from SSE server");
            }
            if (path.startsWith("http://") || path.startsWith("https://")) {
                return path;
            }
            return URI.create(baseUrl).resolve(path).toString();
        }
    }
}
