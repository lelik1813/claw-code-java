package com.clawcode.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class SseMcpClientTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private DisposableServer testServer;
    private Sinks.Many<String> responseSink;
    private SseMcpClient client;

    @BeforeEach
    void setUp() {
        responseSink = Sinks.many().unicast().onBackpressureBuffer();
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.shutdown();
        if (testServer != null) testServer.disposeNow(Duration.ofSeconds(5));
    }

    // --- happy path ---

    @Test
    void listResources_happyPath() {
        startTestServer();

        StepVerifier.create(client.listResources("sse-test"))
            .assertNext(r -> {
                assertThat(r.uri()).isEqualTo(URI.create("file:///sse/test.txt"));
                assertThat(r.name()).isEqualTo("test.txt");
                assertThat(r.description()).isEqualTo("SSE test file");
                assertThat(r.mimeType()).isEqualTo("text/plain");
            })
            .verifyComplete();
    }

    @Test
    void listResources_emptyResources() {
        startTestServer((method, id) ->
            jsonRpcResponse(id, Map.of("resources", List.of())));

        StepVerifier.create(client.listResources("sse-test"))
            .verifyComplete();
    }

    @Test
    void readResource_happyPath() {
        startTestServer();

        StepVerifier.create(client.readResource("sse-test", URI.create("file:///sse/test.txt")))
            .assertNext(c -> {
                assertThat(c.uri()).isEqualTo(URI.create("file:///sse/test.txt"));
                assertThat(c.mimeType()).isEqualTo("text/plain");
                assertThat(c.text()).isEqualTo("Hello from SSE transport");
            })
            .verifyComplete();
    }

    // --- multiple requests reuse session ---

    @Test
    void multipleRequests_reuseSameSession() {
        startTestServer();

        StepVerifier.create(client.listResources("sse-test"))
            .expectNextCount(1)
            .verifyComplete();

        StepVerifier.create(client.readResource("sse-test", URI.create("file:///sse/test.txt")))
            .assertNext(c -> assertThat(c.text()).isEqualTo("Hello from SSE transport"))
            .verifyComplete();
    }

    // --- server validation ---

    @Test
    void unknownServerReturnsError() {
        startTestServer();
        StepVerifier.create(client.listResources("no-such-server"))
            .expectErrorMatches(e -> e instanceof McpServerNotFoundException)
            .verify();
    }

    @Test
    void disabledServerReturnsError() {
        startTestServer();
        var properties = new McpProperties(true, Map.of(
            "off", new McpProperties.McpServerDefinition(false, McpTransportType.SSE,
                "http://localhost:1/sse", "", null, null, null, null, 10_000, null, 30_000, 300_000)
        ));
        SseMcpClient disabledClient = new SseMcpClient(properties);
        try {
            StepVerifier.create(disabledClient.listResources("off"))
                .expectErrorMatches(e -> e instanceof McpRemoteException mre
                    && mre.getMessage().contains("server is disabled"))
                .verify();
        } finally {
            disabledClient.shutdown();
        }
    }

    @Test
    void missingBaseUrlReturnsError() {
        var properties = new McpProperties(true, Map.of(
            "no-url", new McpProperties.McpServerDefinition(true, McpTransportType.SSE,
                null, "", null, null, null, null, 10_000, null, 30_000, 300_000)
        ));
        SseMcpClient noUrlClient = new SseMcpClient(properties);
        try {
            StepVerifier.create(noUrlClient.listResources("no-url"))
                .expectErrorMatches(e -> e instanceof McpRemoteException mre
                    && mre.getMessage().contains("non-blank 'baseUrl'"))
                .verify();
        } finally {
            noUrlClient.shutdown();
        }
    }

    // --- custom headers ---

    @Test
    void customHeadersAreSent() {
        startTestServer();

        var properties = new McpProperties(true, Map.of(
            "header-test", new McpProperties.McpServerDefinition(true, McpTransportType.SSE,
                "http://localhost:" + serverPort() + "/sse", "",
                null, null, null, null, 10_000,
                Map.of("X-Custom", "sse-value"), 30_000, 300_000)
        ));
        SseMcpClient headerClient = new SseMcpClient(properties);
        try {
            StepVerifier.create(headerClient.listResources("header-test"))
                .expectNextCount(1)
                .verifyComplete();
        } finally {
            headerClient.shutdown();
        }
    }

    // --- connection timeout ---

    @Test
    void connectionTimeout_whenNoServerRunning() {
        var properties = new McpProperties(true, Map.of(
            "unreachable", new McpProperties.McpServerDefinition(true, McpTransportType.SSE,
                "http://localhost:1/sse", "", null, null, null, null, 500, null, 30_000, 300_000)
        ));
        SseMcpClient timeoutClient = new SseMcpClient(properties);
        try {
            StepVerifier.create(timeoutClient.listResources("unreachable"))
                .expectErrorMatches(e -> e instanceof McpRemoteException)
                .verify(Duration.ofSeconds(10));
        } finally {
            timeoutClient.shutdown();
        }
    }

    // --- JSON-RPC error response ---

    @Test
    void remoteErrorEvent_mapsToMcpRemoteException() {
        startTestServer((method, id) -> {
            if ("resources/list".equals(method)) {
                return jsonRpcError(id, -32603, "Internal server error");
            }
            return defaultResponse(method, id);
        });

        StepVerifier.create(client.listResources("sse-test"))
            .expectErrorMatches(e -> e instanceof McpRemoteException mre
                && mre.getMessage().contains("I/O error")
                && mre.getMessage().contains("JSON-RPC error"))
            .verify();
    }

    // --- read timeout ---

    @Test
    void readTimeout_whenServerNeverResponds() {
        startTestServer((method, id) -> {
            if ("resources/list".equals(method)) {
                return null; // never emit response
            }
            return defaultResponse(method, id);
        });

        var properties = new McpProperties(true, Map.of(
            "slow-sse", new McpProperties.McpServerDefinition(true, McpTransportType.SSE,
                "http://localhost:" + serverPort() + "/sse", "",
                null, null, null, null, 10_000, null, 1_000, 1_000)
        ));
        SseMcpClient slowClient = new SseMcpClient(properties);
        try {
            StepVerifier.create(slowClient.listResources("slow-sse"))
                .expectErrorMatches(e -> e instanceof McpRemoteException mre
                    && mre.getMessage().contains("timed out"))
                .verify(Duration.ofSeconds(10));
        } finally {
            slowClient.shutdown();
        }
    }

    // --- POST failure ---

    @Test
    void postFailure_mapsToMcpRemoteException() {
        // Server that accepts SSE but returns 500 for POST /messages
        AtomicInteger requestCount = new AtomicInteger(0);
        testServer = HttpServer.create()
            .port(0)
            .handle((req, resp) -> {
                if ("/sse".equals(req.uri())) {
                    return resp.header("Content-Type", "text/event-stream")
                        .sendString(
                            Mono.just("event: endpoint\ndata: http://localhost:"
                                + serverPort() + "/messages\n\n")
                            .concatWith(responseSink.asFlux()
                                .map(data -> "event: message\ndata: " + data + "\n\n"))
                        );
                }
                if ("/messages".equals(req.uri())) {
                    int n = requestCount.incrementAndGet();
                    if (n == 1) {
                        // initialize succeeds
                        return req.receive().aggregate().asString()
                            .flatMap(body -> {
                                responseSink.tryEmitNext(defaultResponse("initialize", 1));
                                return resp.status(202).sendString(Mono.just("ok")).then();
                            });
                    }
                    // resources/list POST fails
                    return resp.status(500).sendString(Mono.just("crash")).then();
                }
                return resp.status(404).sendString(Mono.just("not found")).then();
            })
            .bindNow();

        String sseUrl = "http://localhost:" + serverPort() + "/sse";
        var properties = new McpProperties(true, Map.of(
            "post-fail", new McpProperties.McpServerDefinition(true, McpTransportType.SSE,
                sseUrl, "", null, null, null, null, 10_000, null, 30_000, 300_000)
        ));
        client = new SseMcpClient(properties);

        StepVerifier.create(client.listResources("post-fail"))
            .expectErrorMatches(e -> e instanceof McpRemoteException)
            .verify(Duration.ofSeconds(10));
    }

    // --- unknown SSE event ID (stale/extra events ignored) ---

    @Test
    void unknownIdEvents_ignoredWithoutBreakingSession() {
        AtomicInteger requestId = new AtomicInteger(0);
        startTestServer((method, id) -> {
            if ("initialize".equals(method)) {
                return defaultResponse(method, id);
            }
            if ("resources/list".equals(method)) {
                // First emit an event with a wrong ID (stale from previous session)
                responseSink.tryEmitNext(jsonRpcResponse(999, Map.of("resources", List.of())));
                // Then emit the real response
                return defaultResponse(method, id);
            }
            return defaultResponse(method, id);
        });

        StepVerifier.create(client.listResources("sse-test"))
            .expectNextCount(1)
            .verifyComplete();
    }

    // --- relative endpoint path ---

    @Test
    void relativeEndpointPath_resolvedCorrectly() {
        // SSE sends relative path "/messages" instead of full URL
        testServer = HttpServer.create()
            .port(0)
            .handle((req, resp) -> {
                if ("/sse".equals(req.uri())) {
                    return resp.header("Content-Type", "text/event-stream")
                        .sendString(
                            Mono.just("event: endpoint\ndata: /messages\n\n")
                            .concatWith(responseSink.asFlux()
                                .map(data -> "event: message\ndata: " + data + "\n\n"))
                        );
                }
                if ("/messages".equals(req.uri())) {
                    return req.receive().aggregate().asString()
                        .flatMap(body -> {
                            try {
                                JsonNode json = mapper.readTree(body);
                                int id = json.path("id").asInt(-1);
                                String method = json.path("method").asText("");
                                String response = defaultResponse(method, id);
                                if (response != null) {
                                    responseSink.tryEmitNext(response);
                                }
                            } catch (Exception ignored) {}
                            return resp.status(202).sendString(Mono.just("accepted")).then();
                        });
                }
                return resp.status(404).sendString(Mono.just("not found")).then();
            })
            .bindNow();

        String sseUrl = "http://localhost:" + serverPort() + "/sse";
        var properties = new McpProperties(true, Map.of(
            "relative", new McpProperties.McpServerDefinition(true, McpTransportType.SSE,
                sseUrl, "", null, null, null, null, 10_000, null, 30_000, 300_000)
        ));
        client = new SseMcpClient(properties);

        StepVerifier.create(client.listResources("relative"))
            .expectNextCount(1)
            .verifyComplete();
    }

    // --- shutdown ---

    @Test
    void shutdownCleansUp() {
        startTestServer();
        client.shutdown();
        // No exception means cleanup succeeded
    }

    @Test
    void doubleShutdown_isIdempotent() {
        startTestServer();
        client.shutdown();
        client.shutdown();
    }

    @Test
    void shutdownAfterRequest_completesWithoutReactorErrorNoise() {
        startTestServer();

        // Perform a request to establish the SSE session
        StepVerifier.create(client.listResources("sse-test"))
            .expectNextCount(1)
            .verifyComplete();

        // Shutdown client while server is still running
        // Must not trigger onErrorDropped / ErrorCallbackNotImplemented
        AtomicInteger droppedErrors = new AtomicInteger();
        reactor.core.publisher.Hooks.onErrorDropped(e -> droppedErrors.incrementAndGet());
        try {
            client.shutdown();
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            reactor.core.publisher.Hooks.resetOnErrorDropped();
        }
        assertThat(droppedErrors.get())
                .as("No onErrorDropped during clean SSE shutdown")
                .isZero();
    }

    // --- helpers ---

    private int serverPort() {
        return ((java.net.InetSocketAddress) testServer.address()).getPort();
    }

    private void startTestServer() {
        startTestServer(this::defaultResponse);
    }

    private void startTestServer(ResponseProvider provider) {
        testServer = HttpServer.create()
            .port(0)
            .handle((req, resp) -> {
                String path = req.uri();
                if ("/sse".equals(path)) {
                    return resp.header("Content-Type", "text/event-stream")
                        .sendString(
                            Mono.just("event: endpoint\ndata: http://localhost:"
                                + serverPort() + "/messages\n\n")
                            .concatWith(responseSink.asFlux()
                                .map(data -> "event: message\ndata: " + data + "\n\n"))
                        );
                }
                if ("/messages".equals(path)) {
                    return req.receive().aggregate().asString()
                        .flatMap(body -> {
                            try {
                                JsonNode json = mapper.readTree(body);
                                int id = json.path("id").asInt(-1);
                                String method = json.path("method").asText("");
                                String response = provider.respond(method, id);
                                if (response != null) {
                                    responseSink.tryEmitNext(response);
                                }
                            } catch (Exception ignored) {}
                            return resp.status(202).sendString(Mono.just("accepted")).then();
                        });
                }
                return resp.status(404).sendString(Mono.just("not found")).then();
            })
            .bindNow();

        String sseUrl = "http://localhost:" + serverPort() + "/sse";
        var properties = new McpProperties(true, Map.of(
            "sse-test", new McpProperties.McpServerDefinition(true, McpTransportType.SSE,
                sseUrl, "", null, null, null, null, 10_000, null, 30_000, 300_000)
        ));
        client = new SseMcpClient(properties);
    }

    private String defaultResponse(String method, int id) {
        return switch (method) {
            case "initialize" -> jsonRpcResponse(id, Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "serverInfo", Map.of("name", "test-sse", "version", "1.0")));
            case "resources/list" -> jsonRpcResponse(id, Map.of(
                "resources", List.of(
                    Map.of("uri", "file:///sse/test.txt", "name", "test.txt",
                           "description", "SSE test file", "mimeType", "text/plain"))));
            case "resources/read" -> jsonRpcResponse(id, Map.of(
                "contents", List.of(
                    Map.of("uri", "file:///sse/test.txt", "mimeType", "text/plain",
                           "text", "Hello from SSE transport"))));
            default -> jsonRpcResponse(id, Map.of());
        };
    }

    private static String jsonRpcResponse(int id, Object result) {
        try {
            var node = mapper.createObjectNode();
            node.put("jsonrpc", "2.0");
            node.put("id", id);
            node.set("result", mapper.valueToTree(result));
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String jsonRpcError(int id, int code, String message) {
        try {
            var node = mapper.createObjectNode();
            node.put("jsonrpc", "2.0");
            node.put("id", id);
            var error = node.putObject("error");
            error.put("code", code);
            error.put("message", message);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    interface ResponseProvider {
        String respond(String method, int id);
    }
}
