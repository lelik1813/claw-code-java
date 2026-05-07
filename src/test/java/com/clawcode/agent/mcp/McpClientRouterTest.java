package com.clawcode.agent.mcp;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class McpClientRouterTest {

    private static final McpResource SAMPLE_RESOURCE = new McpResource(
        URI.create("file:///data.txt"), "data", "test resource", "text/plain");

    @Test
    void routesToHttpClientForHttpType() {
        McpClient httpStub = new McpClient() {
            @Override public Flux<McpResource> listResources(String s) {
                return Flux.just(SAMPLE_RESOURCE);
            }
            @Override public Mono<McpResourceContent> readResource(String s, URI u) {
                return Mono.just(new McpResourceContent(u, "text/plain", "http-content"));
            }
        };

        var properties = new McpProperties(true, Map.of(
            "my-http", new McpProperties.McpServerDefinition(true, McpTransportType.HTTP,
                "http://localhost:3001", "", null, null, null, null, 10_000, null, 30_000, 300_000)
        ));

        var router = new McpClientRouter(properties, Map.of(
            McpTransportType.HTTP, httpStub,
            McpTransportType.STDIO, new StdioMcpClient(new McpProperties(true, Map.of())),
            McpTransportType.SSE, new SseMcpClient(new McpProperties(true, Map.of()))
        ));

        StepVerifier.create(router.listResources("my-http"))
            .expectNext(SAMPLE_RESOURCE)
            .verifyComplete();

        StepVerifier.create(router.readResource("my-http", URI.create("file:///x")))
            .assertNext(c -> assertThat(c.text()).isEqualTo("http-content"))
            .verifyComplete();
    }

    @Test
    void routesToStdioClientForStdioType() {
        McpClient stdioStub = new McpClient() {
            @Override public Flux<McpResource> listResources(String s) {
                return Flux.error(new McpRemoteException(s, "STDIO stub", null));
            }
            @Override public Mono<McpResourceContent> readResource(String s, URI u) {
                return Mono.error(new McpRemoteException(s, "STDIO stub", null));
            }
        };

        var properties = new McpProperties(true, Map.of(
            "local", new McpProperties.McpServerDefinition(true, McpTransportType.STDIO,
                null, "", "npx", null, null, null, 10_000, null, 30_000, 300_000)
        ));

        var router = new McpClientRouter(properties, Map.of(
            McpTransportType.STDIO, stdioStub
        ));

        StepVerifier.create(router.listResources("local"))
            .expectErrorMatches(e -> e instanceof McpRemoteException mre
                && mre.getMessage().contains("STDIO stub"))
            .verify();
    }

    @Test
    void routesToSseClientForSseType() {
        McpClient sseStub = new McpClient() {
            @Override public Flux<McpResource> listResources(String s) {
                return Flux.error(new McpRemoteException(s, "SSE stub", null));
            }
            @Override public Mono<McpResourceContent> readResource(String s, URI u) {
                return Mono.error(new McpRemoteException(s, "SSE stub", null));
            }
        };

        var properties = new McpProperties(true, Map.of(
            "events", new McpProperties.McpServerDefinition(true, McpTransportType.SSE,
                "http://localhost:3002/sse", "", null, null, null, null, 10_000, null, 30_000, 300_000)
        ));

        var router = new McpClientRouter(properties, Map.of(
            McpTransportType.SSE, sseStub
        ));

        StepVerifier.create(router.readResource("events", URI.create("file:///x")))
            .expectErrorMatches(e -> e instanceof McpRemoteException mre
                && mre.getMessage().contains("SSE stub"))
            .verify();
    }

    @Test
    void unknownServerReturnsNotFoundError() {
        var properties = new McpProperties(true, Map.of());
        var router = new McpClientRouter(properties);

        StepVerifier.create(router.listResources("no-such-server"))
            .expectErrorMatches(e -> e instanceof McpServerNotFoundException)
            .verify();
    }

    @Test
    void disabledServerReturnsError() {
        var properties = new McpProperties(true, Map.of(
            "off", new McpProperties.McpServerDefinition(false, "http://localhost", "")
        ));

        var router = new McpClientRouter(properties);

        StepVerifier.create(router.listResources("off"))
            .expectErrorMatches(e -> e instanceof McpRemoteException mre
                && mre.getMessage().contains("server is disabled"))
            .verify();
    }

    @Test
    void routerWithAllTransportTypesCreatedFromProperties() {
        McpClient stdioStub = new McpClient() {
            @Override public Flux<McpResource> listResources(String s) {
                return Flux.error(new McpRemoteException(s, "STDIO stub error", null));
            }
            @Override public Mono<McpResourceContent> readResource(String s, URI u) {
                return Mono.error(new McpRemoteException(s, "STDIO stub", null));
            }
        };
        McpClient sseStub = new McpClient() {
            @Override public Flux<McpResource> listResources(String s) {
                return Flux.error(new McpRemoteException(s, "SSE stub error", null));
            }
            @Override public Mono<McpResourceContent> readResource(String s, URI u) {
                return Mono.error(new McpRemoteException(s, "SSE stub", null));
            }
        };

        var properties = new McpProperties(true, Map.of(
            "http-srv", new McpProperties.McpServerDefinition(true, "http://localhost:3001", ""),
            "stdio-srv", new McpProperties.McpServerDefinition(true, McpTransportType.STDIO,
                null, "", "cmd", null, null, null, 10_000, null, 30_000, 300_000),
            "sse-srv", new McpProperties.McpServerDefinition(true, McpTransportType.SSE,
                "http://localhost:3002/sse", "", null, null, null, null, 10_000, null, 30_000, 300_000)
        ));

        var router = new McpClientRouter(properties, Map.of(
            McpTransportType.HTTP, new HttpMcpClient(properties),
            McpTransportType.STDIO, stdioStub,
            McpTransportType.SSE, sseStub
        ));

        // HTTP resolves (may fail on connection, but routing works)
        StepVerifier.create(router.listResources("http-srv"))
            .expectError()
            .verify();

        // STDIO routes to stub
        StepVerifier.create(router.listResources("stdio-srv"))
            .expectErrorMatches(e -> e instanceof McpRemoteException mre
                && mre.getMessage().contains("STDIO stub"))
            .verify();

        // SSE routes to stub
        StepVerifier.create(router.listResources("sse-srv"))
            .expectErrorMatches(e -> e instanceof McpRemoteException mre
                && mre.getMessage().contains("SSE stub"))
            .verify();
    }

    @Test
    void customTransportTypeReturnsUnsupportedError() {
        McpTransportType unknownType = null;
        // Simulate a definition with a type that has no registered client
        var properties = new McpProperties(true, Map.of());
        var router = new McpClientRouter(properties, Map.of()); // no transports registered

        // Manually create definition and test resolve logic
        // Since we can't add to the enum, test with empty transport map
        var propsWithServer = new McpProperties(true, Map.of(
            "my-http", new McpProperties.McpServerDefinition(true, "http://localhost:3001", "")
        ));

        var emptyRouter = new McpClientRouter(propsWithServer, Map.of());

        StepVerifier.create(emptyRouter.listResources("my-http"))
            .expectErrorMatches(e -> e instanceof McpRemoteException mre
                && mre.getMessage().contains("Unsupported transport type"))
            .verify();
    }
}
