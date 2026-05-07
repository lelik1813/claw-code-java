package com.clawcode.agent.mcp;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class McpConfigurationTransportSelectionTest {

    // --- Production router constructor routes by type ---

    @Test
    void httpType_routesThroughHttpClient() {
        var properties = new McpProperties(true, Map.of(
            "api", new McpProperties.McpServerDefinition(true, "http://localhost:1", "")
        ));
        McpClient router = new McpClientRouter(properties);

        // Connection refused → WebClientRequestException → McpRemoteException via onErrorMap
        StepVerifier.create(router.listResources("api"))
            .expectError()
            .verify();
    }

    @Test
    void stdioType_routesThroughStdioClient() {
        var properties = new McpProperties(true, Map.of(
            "local", new McpProperties.McpServerDefinition(true, McpTransportType.STDIO,
                null, "", "nonexistent-command-xyz", List.of(), null, null, 1_000, null, 30_000, 300_000)
        ));
        McpClient router = new McpClientRouter(properties);

        // Process start failure → RuntimeException wrapping IOException
        StepVerifier.create(router.listResources("local"))
            .expectError()
            .verify();
    }

    @Test
    void sseType_routesThroughSseClient() {
        var properties = new McpProperties(true, Map.of(
            "events", new McpProperties.McpServerDefinition(true, McpTransportType.SSE,
                "http://localhost:1/sse", "", null, null, null, null, 1_000, null, 1_000, 300_000)
        ));
        McpClient router = new McpClientRouter(properties);

        // Connection refused from SSE connect
        StepVerifier.create(router.listResources("events"))
            .expectError()
            .verify();
    }

    // --- Missing transport client → controlled error ---

    @Test
    void missingTransportClient_returnsUnsupportedError() {
        var properties = new McpProperties(true, Map.of(
            "orphan", new McpProperties.McpServerDefinition(true, "http://localhost:1", "")
        ));
        McpClient router = new McpClientRouter(properties, Map.of());

        StepVerifier.create(router.listResources("orphan"))
            .expectErrorMatches(e -> e instanceof McpRemoteException mre
                && mre.getMessage().contains("Unsupported transport type: HTTP"))
            .verify();
    }

    @Test
    void missingTransportClient_readResourceAlsoErrors() {
        var properties = new McpProperties(true, Map.of(
            "orphan", new McpProperties.McpServerDefinition(true, McpTransportType.SSE,
                "http://localhost:1/sse", "", null, null, null, null, 10_000, null, 30_000, 300_000)
        ));
        McpClient router = new McpClientRouter(properties, Map.of());

        StepVerifier.create(router.readResource("orphan", URI.create("file:///x")))
            .expectErrorMatches(e -> e instanceof McpRemoteException mre
                && mre.getMessage().contains("Unsupported transport type: SSE"))
            .verify();
    }

    // --- McpConfiguration bean wiring ---

    @Test
    void configurationCreatesRouterAsMcpClient() {
        var properties = new McpProperties(true, Map.of());
        McpConfiguration config = new McpConfiguration();

        McpClient client = config.mcpClientRouter(properties);

        assertThat(client).isInstanceOf(McpClientRouter.class);
    }

    @Test
    void configurationCreatesServiceWithRouter() {
        var properties = new McpProperties(true, Map.of());
        McpConfiguration config = new McpConfiguration();

        McpClient router = config.mcpClientRouter(properties);
        McpService service = config.mcpService(properties, router);

        StepVerifier.create(service.listResources("no-such"))
            .expectErrorMatches(e -> e instanceof McpServerNotFoundException)
            .verify();
    }

    // --- Multiple servers with different transports ---

    @Test
    void multipleServers_differentTransports_routeIndependently() {
        var properties = new McpProperties(true, Map.of(
            "http-srv", new McpProperties.McpServerDefinition(true, "http://localhost:1", ""),
            "stdio-srv", new McpProperties.McpServerDefinition(true, McpTransportType.STDIO,
                null, "", "bad-cmd", null, null, null, 1_000, null, 30_000, 300_000),
            "sse-srv", new McpProperties.McpServerDefinition(true, McpTransportType.SSE,
                "http://localhost:1/sse", "", null, null, null, null, 1_000, null, 1_000, 300_000)
        ));
        McpClient router = new McpClientRouter(properties);

        // All three route through different transports; all fail on connection
        StepVerifier.create(router.listResources("http-srv"))
            .expectError().verify();
        StepVerifier.create(router.listResources("stdio-srv"))
            .expectError().verify();
        StepVerifier.create(router.listResources("sse-srv"))
            .expectError().verify();
    }

    // --- Edge cases ---

    @Test
    void disabledServer_checkedBeforeTransportLookup() {
        var properties = new McpProperties(true, Map.of(
            "off", new McpProperties.McpServerDefinition(false, "http://localhost:1", "")
        ));
        McpClient router = new McpClientRouter(properties, Map.of());

        StepVerifier.create(router.listResources("off"))
            .expectErrorMatches(e -> e instanceof McpRemoteException mre
                && mre.getMessage().contains("server is disabled"))
            .verify();
    }

    @Test
    void unknownServer_notFoundBeforeTransportLookup() {
        var properties = new McpProperties(true, Map.of());
        McpClient router = new McpClientRouter(properties);

        StepVerifier.create(router.readResource("missing", URI.create("file:///x")))
            .expectErrorMatches(e -> e instanceof McpServerNotFoundException
                && e.getMessage().contains("missing"))
            .verify();
    }

    @Test
    void defaultTypeIsHttp_whenConstructedViaShorthand() {
        var properties = new McpProperties(true, Map.of(
            "shorthand", new McpProperties.McpServerDefinition(true, "http://localhost:1", "")
        ));

        // Verify default type is HTTP
        McpProperties.McpServerDefinition def = properties.servers().get("shorthand");
        assertThat(def.type()).isEqualTo(McpTransportType.HTTP);
    }
}
