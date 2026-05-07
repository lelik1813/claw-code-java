package com.clawcode.agent.tools.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawcode.agent.forensics.AuditTrail;
import com.clawcode.agent.forensics.ObservabilityMetrics;
import com.clawcode.agent.mcp.*;
import com.clawcode.agent.tools.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolsTest {

    private static final AuditTrail noopAudit = event -> Mono.empty();
    private static final ObservabilityMetrics noopMetrics = new ObservabilityMetrics(new SimpleMeterRegistry());
    private static final ObjectMapper mapper = new ObjectMapper();

    private Path tempDir;
    private DisposableServer sseServer;
    private DisposableServer httpServer;
    private Sinks.Many<String> sseResponseSink;
    private McpClient router;
    private McpConfiguration mcpConfig = new McpConfiguration();

    @BeforeEach
    void setUp() throws IOException {
        sseResponseSink = Sinks.many().unicast().onBackpressureBuffer();
        tempDir = Files.createTempDirectory("mcp-tools-test");
    }

    @AfterEach
    void tearDown() {
        if (router != null) {
            try {
                router.getClass().getMethod("shutdown").invoke(router);
            } catch (Exception ignored) {}
        }
        if (sseServer != null) sseServer.disposeNow(Duration.ofSeconds(5));
        if (httpServer != null) httpServer.disposeNow(Duration.ofSeconds(5));
        deleteRecursively(tempDir);
    }

    // --- Unit tests: McpListResourcesTool ---

    @Test
    void listResources_successReturnsListOfMaps() {
        McpService service = stubService(
            reactor.core.publisher.Flux.just(
                new McpResource(URI.create("file:///a.txt"), "a", "desc", "text/plain"),
                new McpResource(URI.create("file:///b.json"), "b", "", null)
            ),
            null
        );
        var tool = new McpListResourcesTool(service);

        StepVerifier.create(tool.execute(Map.of("server", "s1"), null))
            .assertNext(obj -> {
                assertThat(obj).isInstanceOf(List.class);
                @SuppressWarnings("unchecked")
                var list = (List<Map<String, Object>>) obj;
                assertThat(list).hasSize(2);
                assertThat(list.get(0)).containsEntry("uri", "file:///a.txt");
                assertThat(list.get(0)).containsEntry("name", "a");
                assertThat(list.get(0)).containsEntry("description", "desc");
                assertThat(list.get(0)).containsEntry("mimeType", "text/plain");
                assertThat(list.get(1)).doesNotContainKey("description");
                assertThat(list.get(1)).doesNotContainKey("mimeType");
            })
            .verifyComplete();
    }

    @Test
    void listResources_emptyServerReturnsEmptyList() {
        McpService service = stubService(reactor.core.publisher.Flux.empty(), null);
        var tool = new McpListResourcesTool(service);

        StepVerifier.create(tool.execute(Map.of("server", "s1"), null))
            .assertNext(obj -> {
                @SuppressWarnings("unchecked")
                var list = (List<?>) obj;
                assertThat(list).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void listResources_serverNotFound_propagatesError() {
        McpService service = stubService(
            reactor.core.publisher.Flux.error(new McpServerNotFoundException("unknown")),
            null
        );
        var tool = new McpListResourcesTool(service);

        StepVerifier.create(tool.execute(Map.of("server", "unknown"), null))
            .expectErrorMatches(e -> e instanceof McpServerNotFoundException
                && e.getMessage().contains("unknown"))
            .verify();
    }

    @Test
    void listResources_remoteError_propagatesAsMcpRemoteException() {
        McpService service = stubService(
            reactor.core.publisher.Flux.error(new McpRemoteException("s1", "HTTP 500", null)),
            null
        );
        var tool = new McpListResourcesTool(service);

        StepVerifier.create(tool.execute(Map.of("server", "s1"), null))
            .expectErrorMatches(e -> e instanceof McpRemoteException
                && e.getMessage().contains("HTTP 500"))
            .verify();
    }

    @Test
    void listResources_stringInput_usesAsServerName() {
        McpService service = stubService(
            reactor.core.publisher.Flux.just(new McpResource(URI.create("x://r"), "r", "", null)),
            null
        );
        var tool = new McpListResourcesTool(service);

        StepVerifier.create(tool.execute("s1", null))
            .assertNext(obj -> {
                @SuppressWarnings("unchecked")
                var list = (List<?>) obj;
                assertThat(list).hasSize(1);
            })
            .verifyComplete();
    }

    // --- Unit tests: McpReadResourceTool ---

    @Test
    void readResource_successReturnsContentMap() {
        McpService service = stubService(null,
            Mono.just(new McpResourceContent(
                URI.create("file:///data.txt"), "text/plain", "hello")));
        var tool = new McpReadResourceTool(service);

        StepVerifier.create(
            tool.execute(Map.of("server", "s1", "uri", "file:///data.txt"), null))
            .assertNext(obj -> {
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) obj;
                assertThat(map)
                    .containsEntry("uri", "file:///data.txt")
                    .containsEntry("mimeType", "text/plain")
                    .containsEntry("text", "hello");
            })
            .verifyComplete();
    }

    @Test
    void readResource_missingServer_returnsError() {
        var tool = new McpReadResourceTool(stubService(null, Mono.empty()));

        StepVerifier.create(
            tool.execute(Map.of("uri", "file:///x"), null))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().contains("server is required"))
            .verify();
    }

    @Test
    void readResource_missingUri_returnsError() {
        var tool = new McpReadResourceTool(stubService(null, Mono.empty()));

        StepVerifier.create(
            tool.execute(Map.of("server", "s1"), null))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().contains("uri is required"))
            .verify();
    }

    @Test
    void readResource_invalidUri_returnsError() {
        var tool = new McpReadResourceTool(stubService(null, Mono.empty()));

        StepVerifier.create(
            tool.execute(Map.of("server", "s1", "uri", "not a uri ###"), null))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().contains("invalid uri"))
            .verify();
    }

    @Test
    void readResource_remoteError_propagates() {
        McpService service = stubService(null,
            Mono.error(new McpRemoteException("s1", "connection refused", null)));
        var tool = new McpReadResourceTool(service);

        StepVerifier.create(
            tool.execute(Map.of("server", "s1", "uri", "file:///x"), null))
            .expectErrorMatches(e -> e instanceof McpRemoteException
                && e.getMessage().contains("connection refused"))
            .verify();
    }

    @Test
    void readResource_serverNotFound_propagates() {
        McpService service = stubService(null,
            Mono.error(new McpServerNotFoundException("gone")));
        var tool = new McpReadResourceTool(service);

        StepVerifier.create(
            tool.execute(Map.of("server", "gone", "uri", "file:///x"), null))
            .expectErrorMatches(e -> e instanceof McpServerNotFoundException)
            .verify();
    }

    @Test
    void readResource_nullInput_returnsMissingServer() {
        var tool = new McpReadResourceTool(stubService(null, Mono.empty()));

        StepVerifier.create(tool.execute(null, null))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().contains("server is required"))
            .verify();
    }

    // --- Unit tests: integration through DefaultToolExecutor ---

    @Test
    void listResources_throughExecutor_success() {
        McpService service = stubService(
            reactor.core.publisher.Flux.just(new McpResource(URI.create("x://r"), "r", "d", "text/plain")),
            null
        );
        var listTool = new McpListResourcesTool(service);
        var registry = registryOf(listTool);
        var executor = new DefaultToolExecutor(registry, allowAll(), noopAudit, noopMetrics, List.of());

        var request = new ToolUseRequest("c1", "mcp_list_resources", Map.of("server", "s1"));

        StepVerifier.create(executor.execute(request, ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isFalse();
                assertThat(r.output()).isInstanceOf(List.class);
            })
            .verifyComplete();
    }

    @Test
    void readResource_throughExecutor_errorBecomesToolResultError() {
        McpService service = stubService(null,
            Mono.error(new McpRemoteException("s1", "refused", null)));
        var readTool = new McpReadResourceTool(service);
        var registry = registryOf(readTool);
        var executor = new DefaultToolExecutor(registry, allowAll(), noopAudit, noopMetrics, List.of());

        var request = new ToolUseRequest("c2", "mcp_read_resource",
            Map.of("server", "s1", "uri", "file:///x"));

        StepVerifier.create(executor.execute(request, ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.errorMessage()).contains("refused");
            })
            .verifyComplete();
    }

    // --- STDIO integration through full tool chain ---

    @Test
    void stdio_listResources_throughToolAndExecutor() throws IOException {
        Path script = createMockMcpServer(tempDir);
        String command = isWindows() ? "cmd" : "sh";
        List<String> args = isWindows()
            ? List.of("/c", script.toString())
            : List.of(script.toString());

        var def = new McpProperties.McpServerDefinition(true, McpTransportType.STDIO,
            null, "", command, args, Map.of(), tempDir.toString(), 10_000, null, 30_000, 300_000);
        var properties = new McpProperties(true, Map.of("stdio-srv", def));

        router = mcpConfig.mcpClientRouter(properties);
        McpService service = new McpService(properties, router);
        var tool = new McpListResourcesTool(service);
        var executor = new DefaultToolExecutor(registryOf(tool), allowAll(), noopAudit, noopMetrics, List.of());

        var request = new ToolUseRequest("t1", "mcp_list_resources", Map.of("server", "stdio-srv"));

        StepVerifier.create(executor.execute(request, ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isFalse();
                @SuppressWarnings("unchecked")
                var list = (List<Map<String, Object>>) r.output();
                assertThat(list).hasSize(1);
                assertThat(list.get(0)).containsEntry("uri", "file:///data/test.txt");
                assertThat(list.get(0)).containsEntry("name", "test.txt");
            })
            .verifyComplete();
    }

    @Test
    void stdio_readResource_throughToolAndExecutor() throws IOException {
        Path script = createMockMcpServer(tempDir);
        String command = isWindows() ? "cmd" : "sh";
        List<String> args = isWindows()
            ? List.of("/c", script.toString())
            : List.of(script.toString());

        var def = new McpProperties.McpServerDefinition(true, McpTransportType.STDIO,
            null, "", command, args, Map.of(), tempDir.toString(), 10_000, null, 30_000, 300_000);
        var properties = new McpProperties(true, Map.of("stdio-srv", def));

        router = mcpConfig.mcpClientRouter(properties);
        McpService service = new McpService(properties, router);
        var tool = new McpReadResourceTool(service);
        var executor = new DefaultToolExecutor(registryOf(tool), allowAll(), noopAudit, noopMetrics, List.of());

        var request = new ToolUseRequest("t2", "mcp_read_resource",
            Map.of("server", "stdio-srv", "uri", "file:///data/test.txt"));

        StepVerifier.create(executor.execute(request, ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isFalse();
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) r.output();
                assertThat(map).containsEntry("text", "Hello from MCP stdio");
                assertThat(map).containsEntry("mimeType", "text/plain");
            })
            .verifyComplete();
    }

    @Test
    void stdio_listAndRead_sequentialThroughExecutor() throws IOException {
        Path script = createMockMcpServer(tempDir);
        String command = isWindows() ? "cmd" : "sh";
        List<String> args = isWindows()
            ? List.of("/c", script.toString())
            : List.of(script.toString());

        var def = new McpProperties.McpServerDefinition(true, McpTransportType.STDIO,
            null, "", command, args, Map.of(), tempDir.toString(), 10_000, null, 30_000, 300_000);
        var properties = new McpProperties(true, Map.of("stdio-srv", def));

        router = mcpConfig.mcpClientRouter(properties);
        McpService service = new McpService(properties, router);
        var listTool = new McpListResourcesTool(service);
        var readTool = new McpReadResourceTool(service);
        var executor = new DefaultToolExecutor(registryOf(listTool, readTool), allowAll(), noopAudit, noopMetrics, List.of());

        // First: list resources
        var listReq = new ToolUseRequest("t3", "mcp_list_resources", Map.of("server", "stdio-srv"));
        StepVerifier.create(executor.execute(listReq, ctx()))
            .assertNext(r -> assertThat(r.isError()).isFalse())
            .verifyComplete();

        // Second: read resource (reuses same STDIO process)
        var readReq = new ToolUseRequest("t4", "mcp_read_resource",
            Map.of("server", "stdio-srv", "uri", "file:///data/test.txt"));
        StepVerifier.create(executor.execute(readReq, ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isFalse();
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) r.output();
                assertThat(map).containsEntry("text", "Hello from MCP stdio");
            })
            .verifyComplete();
    }

    // --- SSE integration through full tool chain ---

    @Test
    void sse_listResources_throughToolAndExecutor() {
        startSseTestServer();

        var properties = new McpProperties(true, Map.of(
            "sse-srv", new McpProperties.McpServerDefinition(true, McpTransportType.SSE,
                "http://localhost:" + ssePort() + "/sse", "", null, null, null, null, 10_000, null, 30_000, 300_000)
        ));

        router = mcpConfig.mcpClientRouter(properties);
        McpService service = new McpService(properties, router);
        var tool = new McpListResourcesTool(service);
        var executor = new DefaultToolExecutor(registryOf(tool), allowAll(), noopAudit, noopMetrics, List.of());

        var request = new ToolUseRequest("t5", "mcp_list_resources", Map.of("server", "sse-srv"));

        StepVerifier.create(executor.execute(request, ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isFalse();
                @SuppressWarnings("unchecked")
                var list = (List<Map<String, Object>>) r.output();
                assertThat(list).hasSize(1);
                assertThat(list.get(0)).containsEntry("uri", "file:///sse/test.txt");
                assertThat(list.get(0)).containsEntry("name", "test.txt");
            })
            .verifyComplete();
    }

    @Test
    void sse_readResource_throughToolAndExecutor() {
        startSseTestServer();

        var properties = new McpProperties(true, Map.of(
            "sse-srv", new McpProperties.McpServerDefinition(true, McpTransportType.SSE,
                "http://localhost:" + ssePort() + "/sse", "", null, null, null, null, 10_000, null, 30_000, 300_000)
        ));

        router = mcpConfig.mcpClientRouter(properties);
        McpService service = new McpService(properties, router);
        var tool = new McpReadResourceTool(service);
        var executor = new DefaultToolExecutor(registryOf(tool), allowAll(), noopAudit, noopMetrics, List.of());

        var request = new ToolUseRequest("t6", "mcp_read_resource",
            Map.of("server", "sse-srv", "uri", "file:///sse/test.txt"));

        StepVerifier.create(executor.execute(request, ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isFalse();
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) r.output();
                assertThat(map).containsEntry("text", "Hello from SSE transport");
                assertThat(map).containsEntry("mimeType", "text/plain");
            })
            .verifyComplete();
    }

    @Test
    void sse_listAndRead_sequentialThroughExecutor() {
        startSseTestServer();

        var properties = new McpProperties(true, Map.of(
            "sse-srv", new McpProperties.McpServerDefinition(true, McpTransportType.SSE,
                "http://localhost:" + ssePort() + "/sse", "", null, null, null, null, 10_000, null, 30_000, 300_000)
        ));

        router = mcpConfig.mcpClientRouter(properties);
        McpService service = new McpService(properties, router);
        var listTool = new McpListResourcesTool(service);
        var readTool = new McpReadResourceTool(service);
        var executor = new DefaultToolExecutor(registryOf(listTool, readTool), allowAll(), noopAudit, noopMetrics, List.of());

        // First: list
        var listReq = new ToolUseRequest("t7", "mcp_list_resources", Map.of("server", "sse-srv"));
        StepVerifier.create(executor.execute(listReq, ctx()))
            .assertNext(r -> assertThat(r.isError()).isFalse())
            .verifyComplete();

        // Second: read (reuses SSE session)
        var readReq = new ToolUseRequest("t8", "mcp_read_resource",
            Map.of("server", "sse-srv", "uri", "file:///sse/test.txt"));
        StepVerifier.create(executor.execute(readReq, ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isFalse();
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) r.output();
                assertThat(map).containsEntry("text", "Hello from SSE transport");
            })
            .verifyComplete();
    }

    // --- HTTP integration through full tool chain ---

    @Test
    void http_listResources_throughToolAndExecutor() {
        startHttpTestServer();

        String baseUrl = "http://localhost:" + httpPort();
        var def = new McpProperties.McpServerDefinition(true, baseUrl, "");
        var properties = new McpProperties(true, Map.of("http-srv", def));

        router = mcpConfig.mcpClientRouter(properties);
        McpService service = new McpService(properties, router);
        var tool = new McpListResourcesTool(service);
        var executor = new DefaultToolExecutor(registryOf(tool), allowAll(), noopAudit, noopMetrics, List.of());

        var request = new ToolUseRequest("t9", "mcp_list_resources", Map.of("server", "http-srv"));

        StepVerifier.create(executor.execute(request, ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isFalse();
                @SuppressWarnings("unchecked")
                var list = (List<Map<String, Object>>) r.output();
                assertThat(list).hasSize(1);
                assertThat(list.get(0)).containsEntry("uri", "file:///http/test.txt");
                assertThat(list.get(0)).containsEntry("name", "test.txt");
            })
            .verifyComplete();
    }

    @Test
    void http_readResource_throughToolAndExecutor() {
        startHttpTestServer();

        String baseUrl = "http://localhost:" + httpPort();
        var def = new McpProperties.McpServerDefinition(true, baseUrl, "");
        var properties = new McpProperties(true, Map.of("http-srv", def));

        router = mcpConfig.mcpClientRouter(properties);
        McpService service = new McpService(properties, router);
        var tool = new McpReadResourceTool(service);
        var executor = new DefaultToolExecutor(registryOf(tool), allowAll(), noopAudit, noopMetrics, List.of());

        var request = new ToolUseRequest("t10", "mcp_read_resource",
            Map.of("server", "http-srv", "uri", "file:///http/test.txt"));

        StepVerifier.create(executor.execute(request, ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isFalse();
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) r.output();
                assertThat(map).containsEntry("text", "Hello from HTTP transport");
                assertThat(map).containsEntry("mimeType", "text/plain");
            })
            .verifyComplete();
    }

    @Test
    void http_listAndRead_sequentialThroughExecutor() {
        startHttpTestServer();

        String baseUrl = "http://localhost:" + httpPort();
        var def = new McpProperties.McpServerDefinition(true, baseUrl, "");
        var properties = new McpProperties(true, Map.of("http-srv", def));

        router = mcpConfig.mcpClientRouter(properties);
        McpService service = new McpService(properties, router);
        var listTool = new McpListResourcesTool(service);
        var readTool = new McpReadResourceTool(service);
        var executor = new DefaultToolExecutor(registryOf(listTool, readTool), allowAll(), noopAudit, noopMetrics, List.of());

        // List
        var listReq = new ToolUseRequest("t11", "mcp_list_resources", Map.of("server", "http-srv"));
        StepVerifier.create(executor.execute(listReq, ctx()))
            .assertNext(r -> assertThat(r.isError()).isFalse())
            .verifyComplete();

        // Read
        var readReq = new ToolUseRequest("t12", "mcp_read_resource",
            Map.of("server", "http-srv", "uri", "file:///http/test.txt"));
        StepVerifier.create(executor.execute(readReq, ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isFalse();
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) r.output();
                assertThat(map).containsEntry("text", "Hello from HTTP transport");
            })
            .verifyComplete();
    }

    // --- Router integration: same tool calls route to correct transport ---

    @Test
    void router_routesStdioAndHttp_throughToolAndExecutor() throws IOException {
        // Start HTTP server
        startHttpTestServer();

        // Prepare STDIO server
        Path script = createMockMcpServer(tempDir);
        String command = isWindows() ? "cmd" : "sh";
        List<String> args = isWindows()
            ? List.of("/c", script.toString())
            : List.of(script.toString());

        var httpDef = new McpProperties.McpServerDefinition(true, "http://localhost:" + httpPort(), "");
        var stdioDef = new McpProperties.McpServerDefinition(true, McpTransportType.STDIO,
            null, "", command, args, Map.of(), tempDir.toString(), 10_000, null, 30_000, 300_000);
        var properties = new McpProperties(true, Map.of("http-srv", httpDef, "stdio-srv", stdioDef));

        router = mcpConfig.mcpClientRouter(properties);
        McpService service = new McpService(properties, router);
        var listTool = new McpListResourcesTool(service);
        var executor = new DefaultToolExecutor(registryOf(listTool), allowAll(), noopAudit, noopMetrics, List.of());

        // HTTP transport
        StepVerifier.create(executor.execute(
            new ToolUseRequest("h1", "mcp_list_resources", Map.of("server", "http-srv")), ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isFalse();
                @SuppressWarnings("unchecked")
                var list = (List<Map<String, Object>>) r.output();
                assertThat(list.get(0)).containsEntry("uri", "file:///http/test.txt");
            })
            .verifyComplete();

        // STDIO transport
        StepVerifier.create(executor.execute(
            new ToolUseRequest("s1", "mcp_list_resources", Map.of("server", "stdio-srv")), ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isFalse();
                @SuppressWarnings("unchecked")
                var list = (List<Map<String, Object>>) r.output();
                assertThat(list.get(0)).containsEntry("uri", "file:///data/test.txt");
            })
            .verifyComplete();
    }

    @Test
    void router_unknownServer_throughExecutor_givesToolError() {
        var properties = new McpProperties(true, Map.of());
        router = mcpConfig.mcpClientRouter(properties);
        McpService service = new McpService(properties, router);
        var tool = new McpListResourcesTool(service);
        var executor = new DefaultToolExecutor(registryOf(tool), allowAll(), noopAudit, noopMetrics, List.of());

        var request = new ToolUseRequest("e1", "mcp_list_resources", Map.of("server", "nonexistent"));

        StepVerifier.create(executor.execute(request, ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.errorMessage()).contains("nonexistent");
            })
            .verifyComplete();
    }

    @Test
    void router_disabledServer_throughExecutor_givesToolError() {
        var def = new McpProperties.McpServerDefinition(false, "http://localhost:1", "");
        var properties = new McpProperties(true, Map.of("disabled-srv", def));
        router = mcpConfig.mcpClientRouter(properties);
        McpService service = new McpService(properties, router);
        var tool = new McpReadResourceTool(service);
        var executor = new DefaultToolExecutor(registryOf(tool), allowAll(), noopAudit, noopMetrics, List.of());

        var request = new ToolUseRequest("e2", "mcp_read_resource",
            Map.of("server", "disabled-srv", "uri", "file:///x"));

        StepVerifier.create(executor.execute(request, ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.errorMessage()).contains("disabled");
            })
            .verifyComplete();
    }

    @Test
    void mcpDisabled_throughExecutor_givesToolError() {
        var properties = new McpProperties(false, Map.of());
        router = mcpConfig.mcpClientRouter(properties);
        McpService service = new McpService(properties, router);
        var tool = new McpListResourcesTool(service);
        var executor = new DefaultToolExecutor(registryOf(tool), allowAll(), noopAudit, noopMetrics, List.of());

        var request = new ToolUseRequest("e3", "mcp_list_resources", Map.of("server", "any"));

        StepVerifier.create(executor.execute(request, ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.errorMessage()).containsAnyOf("disabled", "not found", "not enabled");
            })
            .verifyComplete();
    }

    // === Helpers ===

    private static McpService stubService(
        reactor.core.publisher.Flux<McpResource> listResult,
        Mono<McpResourceContent> readResult
    ) {
        var serverDef = new McpProperties.McpServerDefinition(true, "http://localhost", "");
        return new McpService(
            new McpProperties(true, Map.of("s1", serverDef, "gone", serverDef)),
            new McpClient() {
                @Override
                public reactor.core.publisher.Flux<McpResource> listResources(String serverName) {
                    return listResult != null ? listResult : reactor.core.publisher.Flux.empty();
                }
                @Override
                public Mono<McpResourceContent> readResource(String serverName, URI uri) {
                    return readResult != null ? readResult : Mono.empty();
                }
            }
        );
    }

    private static ToolPermissionPolicy allowAll() {
        return (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow());
    }

    private static ToolExecutionContext ctx() {
        return new ToolExecutionContext("t", "s", "m", null);
    }

    private static ToolRegistry registryOf(Tool... tools) {
        return new ToolRegistry() {
            @Override
            public java.util.Optional<Tool> findByName(String name) {
                for (Tool t : tools) {
                    if (t.name().equals(name)) return java.util.Optional.of(t);
                }
                return java.util.Optional.empty();
            }
            @Override
            public java.util.Set<String> listNames() {
                return java.util.Set.of();
            }
        };
    }

    // --- SSE test server ---

    private int ssePort() {
        return ((InetSocketAddress) sseServer.address()).getPort();
    }

    private void startSseTestServer() {
        startSseTestServer(this::sseDefaultResponse);
    }

    private void startSseTestServer(SseResponseProvider provider) {
        sseServer = HttpServer.create()
            .port(0)
            .handle((req, resp) -> {
                String path = req.uri();
                if ("/sse".equals(path)) {
                    return resp.header("Content-Type", "text/event-stream")
                        .sendString(
                            Mono.just("event: endpoint\ndata: http://localhost:"
                                + ssePort() + "/messages\n\n")
                            .concatWith(sseResponseSink.asFlux()
                                .map(data -> "event: message\ndata: " + data + "\n\n"))
                        );
                }
                if ("/messages".equals(path)) {
                    return req.receive().aggregate().asString()
                        .flatMap(body -> {
                            try {
                                var json = mapper.readTree(body);
                                int id = json.path("id").asInt(-1);
                                String method = json.path("method").asText("");
                                String response = provider.respond(method, id);
                                if (response != null) {
                                    sseResponseSink.tryEmitNext(response);
                                }
                            } catch (Exception ignored) {}
                            return resp.status(202).sendString(Mono.just("accepted")).then();
                        });
                }
                return resp.status(404).sendString(Mono.just("not found")).then();
            })
            .bindNow();
    }

    private String sseDefaultResponse(String method, int id) {
        return switch (method) {
            case "initialize" -> sseJsonRpcResponse(id, Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "serverInfo", Map.of("name", "test-sse", "version", "1.0")));
            case "resources/list" -> sseJsonRpcResponse(id, Map.of(
                "resources", List.of(
                    Map.of("uri", "file:///sse/test.txt", "name", "test.txt",
                        "description", "SSE test file", "mimeType", "text/plain"))));
            case "resources/read" -> sseJsonRpcResponse(id, Map.of(
                "contents", List.of(
                    Map.of("uri", "file:///sse/test.txt", "mimeType", "text/plain",
                        "text", "Hello from SSE transport"))));
            default -> sseJsonRpcResponse(id, Map.of());
        };
    }

    private static String sseJsonRpcResponse(int id, Object result) {
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

    @FunctionalInterface
    interface SseResponseProvider {
        String respond(String method, int id);
    }

    // --- HTTP test server (Reactor Netty) ---

    private int httpPort() {
        return ((InetSocketAddress) httpServer.address()).getPort();
    }

    private void startHttpTestServer() {
        httpServer = HttpServer.create()
            .port(0)
            .handle((req, resp) -> {
                String path = req.uri();
                if ("/resources".equals(path) && "GET".equals(req.method().name())) {
                    String body = """
                        {"resources":[{"uri":"file:///http/test.txt","name":"test.txt","description":"HTTP test file","mimeType":"text/plain"}]}
                        """;
                    return resp.header("Content-Type", "application/json")
                        .sendString(Mono.just(body));
                }
                if ("/resources/read".equals(path) && "POST".equals(req.method().name())) {
                    String body = """
                        {"contents":[{"uri":"file:///http/test.txt","mimeType":"text/plain","text":"Hello from HTTP transport"}]}
                        """;
                    return resp.header("Content-Type", "application/json")
                        .sendString(Mono.just(body));
                }
                return resp.status(404).sendString(Mono.just("not found")).then();
            })
            .bindNow();
    }

    // --- STDIO mock server script ---

    private Path createMockMcpServer(Path dir) throws IOException {
        if (isWindows()) {
            Path script = dir.resolve("mock_mcp.bat");
            Files.writeString(script, """
                @echo off
                setlocal enabledelayedexpansion
                set /p LINE=
                echo {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"mock","version":"1.0"}}}
                :loop
                set /p LINE=
                if "!LINE!"=="" exit /b 0
                echo !LINE!| findstr /C:"notifications/" > NUL 2>&1
                if !ERRORLEVEL!==0 goto loop
                echo !LINE!| findstr /C:"resources/list" > NUL 2>&1
                if !ERRORLEVEL!==0 (
                    echo {"jsonrpc":"2.0","id":2,"result":{"resources":[{"uri":"file:///data/test.txt","name":"test.txt","description":"A test file","mimeType":"text/plain"}]}}
                    goto loop
                )
                echo !LINE!| findstr /C:"resources/read" > NUL 2>&1
                if !ERRORLEVEL!==0 (
                    echo {"jsonrpc":"2.0","id":3,"result":{"contents":[{"uri":"file:///data/test.txt","mimeType":"text/plain","text":"Hello from MCP stdio"}]}}
                    goto loop
                )
                echo {"jsonrpc":"2.0","id":99,"result":{}}
                goto loop
                """);
            return script;
        }
        Path script = dir.resolve("mock_mcp.sh");
        Files.writeString(script, """
            #!/bin/sh
            read LINE
            echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"mock","version":"1.0"}}}'
            while read LINE; do
              case "$LINE" in
                *"notifications/"*)
                  ;;
                *resources/list*)
                  echo '{"jsonrpc":"2.0","id":2,"result":{"resources":[{"uri":"file:///data/test.txt","name":"test.txt","description":"A test file","mimeType":"text/plain"}]}}'
                  ;;
                *resources/read*)
                  echo '{"jsonrpc":"2.0","id":3,"result":{"contents":[{"uri":"file:///data/test.txt","mimeType":"text/plain","text":"Hello from MCP stdio"}]}}'
                  ;;
                *)
                  echo '{"jsonrpc":"2.0","id":99,"result":{}}'
                  ;;
              esac
            done
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) return;
        try {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
        } catch (IOException ignored) {}
    }
}
