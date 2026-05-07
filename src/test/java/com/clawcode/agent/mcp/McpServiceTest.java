package com.clawcode.agent.mcp;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class McpServiceTest {

    private static final McpResource SAMPLE_RESOURCE = new McpResource(
        URI.create("file:///data.txt"), "data", "test", "text/plain");

    private static final McpResourceContent SAMPLE_CONTENT = new McpResourceContent(
        URI.create("file:///data.txt"), "text/plain", "hello");

    // --- enabled guard ---

    @Test
    void listResources_disabledSubsystem_throwsDisabledException() {
        var properties = new McpProperties(false, Map.of());
        McpClient stub = new McpClient() {
            @Override public Flux<McpResource> listResources(String s) {
                return Flux.just(SAMPLE_RESOURCE);
            }
            @Override public Mono<McpResourceContent> readResource(String s, URI u) {
                return Mono.just(SAMPLE_CONTENT);
            }
        };
        McpService service = new McpService(properties, stub);

        StepVerifier.create(service.listResources("any"))
            .expectErrorMatches(e -> e instanceof McpDisabledException)
            .verify();
    }

    @Test
    void readResource_disabledSubsystem_throwsDisabledException() {
        var properties = new McpProperties(false, Map.of());
        McpClient stub = noopClient();
        McpService service = new McpService(properties, stub);

        StepVerifier.create(service.readResource("any", URI.create("file:///x")))
            .expectErrorMatches(e -> e instanceof McpDisabledException)
            .verify();
    }

    // --- server name guard ---

    @Test
    void listResources_blankServerName_throwsIllegalArgument() {
        var properties = new McpProperties(true, Map.of());
        McpService service = new McpService(properties, noopClient());

        StepVerifier.create(service.listResources(""))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().contains("serverName is required"))
            .verify();
    }

    @Test
    void listResources_nullServerName_throwsIllegalArgument() {
        var properties = new McpProperties(true, Map.of());
        McpService service = new McpService(properties, noopClient());

        StepVerifier.create(service.listResources(null))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().contains("serverName is required"))
            .verify();
    }

    @Test
    void readResource_blankServerName_throwsIllegalArgument() {
        var properties = new McpProperties(true, Map.of());
        McpService service = new McpService(properties, noopClient());

        StepVerifier.create(service.readResource("  ", URI.create("file:///x")))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException)
            .verify();
    }

    // --- server exists guard ---

    @Test
    void listResources_unknownServer_throwsServerNotFound() {
        var properties = new McpProperties(true, Map.of());
        McpService service = new McpService(properties, noopClient());

        StepVerifier.create(service.listResources("no-such-server"))
            .expectErrorMatches(e -> e instanceof McpServerNotFoundException)
            .verify();
    }

    @Test
    void readResource_unknownServer_throwsServerNotFound() {
        var properties = new McpProperties(true, Map.of());
        McpService service = new McpService(properties, noopClient());

        StepVerifier.create(service.readResource("no-such-server", URI.create("file:///x")))
            .expectErrorMatches(e -> e instanceof McpServerNotFoundException)
            .verify();
    }

    // --- delegation to McpClient ---

    @Test
    void listResources_delegatesToClient() {
        var def = new McpProperties.McpServerDefinition(true, "http://localhost:3001", "");
        var properties = new McpProperties(true, Map.of("my-server", def));

        McpClient client = new McpClient() {
            @Override public Flux<McpResource> listResources(String s) {
                return Flux.just(SAMPLE_RESOURCE);
            }
            @Override public Mono<McpResourceContent> readResource(String s, URI u) {
                return Mono.just(SAMPLE_CONTENT);
            }
        };
        McpService service = new McpService(properties, client);

        StepVerifier.create(service.listResources("my-server"))
            .assertNext(r -> {
                assertThat(r.uri()).isEqualTo(URI.create("file:///data.txt"));
                assertThat(r.name()).isEqualTo("data");
                assertThat(r.mimeType()).isEqualTo("text/plain");
            })
            .verifyComplete();
    }

    @Test
    void readResource_delegatesToClient() {
        var def = new McpProperties.McpServerDefinition(true, "http://localhost:3001", "");
        var properties = new McpProperties(true, Map.of("my-server", def));
        McpService service = new McpService(properties, directClient());

        StepVerifier.create(service.readResource("my-server", URI.create("file:///data.txt")))
            .assertNext(c -> {
                assertThat(c.uri()).isEqualTo(URI.create("file:///data.txt"));
                assertThat(c.text()).isEqualTo("hello");
            })
            .verifyComplete();
    }

    @Test
    void listResources_propagatesTransportError() {
        var def = new McpProperties.McpServerDefinition(true, "http://localhost:3001", "");
        var properties = new McpProperties(true, Map.of("srv", def));

        McpClient client = new McpClient() {
            @Override public Flux<McpResource> listResources(String s) {
                return Flux.error(new McpRemoteException(s, "connection refused", null));
            }
            @Override public Mono<McpResourceContent> readResource(String s, URI u) {
                return Mono.error(new McpRemoteException(s, "connection refused", null));
            }
        };
        McpService service = new McpService(properties, client);

        StepVerifier.create(service.listResources("srv"))
            .expectErrorMatches(e -> e instanceof McpRemoteException mre
                && mre.getMessage().contains("connection refused"))
            .verify();
    }

    // --- integration: McpService → McpClientRouter (disabled per-server) ---

    @Test
    void listResources_routerRejectsDisabledServer() {
        var def = new McpProperties.McpServerDefinition(false, McpTransportType.HTTP,
            "http://localhost:3001", "", null, null, null, null, 10_000, null, 30_000, 300_000);
        var properties = new McpProperties(true, Map.of("off", def));
        McpClient router = new McpClientRouter(properties);
        McpService service = new McpService(properties, router);

        StepVerifier.create(service.listResources("off"))
            .expectErrorMatches(e -> e instanceof McpRemoteException mre
                && mre.getMessage().contains("server is disabled"))
            .verify();
    }

    // --- helpers ---

    private static McpClient noopClient() {
        return new McpClient() {
            @Override public Flux<McpResource> listResources(String s) {
                return Flux.empty();
            }
            @Override public Mono<McpResourceContent> readResource(String s, URI u) {
                return Mono.empty();
            }
        };
    }

    private static McpClient directClient() {
        return new McpClient() {
            @Override public Flux<McpResource> listResources(String s) {
                return Flux.just(SAMPLE_RESOURCE);
            }
            @Override public Mono<McpResourceContent> readResource(String s, URI u) {
                return Mono.just(SAMPLE_CONTENT);
            }
        };
    }
}
