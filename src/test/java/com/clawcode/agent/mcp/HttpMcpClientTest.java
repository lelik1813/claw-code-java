package com.clawcode.agent.mcp;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class HttpMcpClientTest {

    private MockWebServer server;
    private HttpMcpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        String baseUrl = server.url("/").toString();
        McpProperties.McpServerDefinition def =
            new McpProperties.McpServerDefinition(true, baseUrl, "test-token");
        McpProperties properties = new McpProperties(true,
            Map.of("test-server", def));

        client = new HttpMcpClient(properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void listResources_happyPath() {
        server.enqueue(new MockResponse()
            .setBody("""
                {"resources":[
                  {"uri":"file:///a.txt","name":"a","description":"file a","mimeType":"text/plain"},
                  {"uri":"file:///b.json","name":"b","mimeType":"application/json"}
                ]}
                """)
            .setHeader("Content-Type", "application/json"));

        StepVerifier.create(client.listResources("test-server"))
            .assertNext(r -> {
                assertThat(r.uri()).isEqualTo(URI.create("file:///a.txt"));
                assertThat(r.name()).isEqualTo("a");
                assertThat(r.description()).isEqualTo("file a");
                assertThat(r.mimeType()).isEqualTo("text/plain");
            })
            .assertNext(r -> {
                assertThat(r.uri()).isEqualTo(URI.create("file:///b.json"));
                assertThat(r.description()).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void listResources_sendsBearerAuth() throws InterruptedException {
        server.enqueue(new MockResponse()
            .setBody("{\"resources\":[]}")
            .setHeader("Content-Type", "application/json"));

        StepVerifier.create(client.listResources("test-server"))
            .verifyComplete();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer test-token");
    }

    @Test
    void listResources_emptyResourcesArray() {
        server.enqueue(new MockResponse()
            .setBody("{\"resources\":[]}")
            .setHeader("Content-Type", "application/json"));

        StepVerifier.create(client.listResources("test-server"))
            .verifyComplete();
    }

    @Test
    void listResources_missingResourcesKey() {
        server.enqueue(new MockResponse()
            .setBody("{\"status\":\"ok\"}")
            .setHeader("Content-Type", "application/json"));

        StepVerifier.create(client.listResources("test-server"))
            .verifyComplete();
    }

    @Test
    void listResources_invalidUri_mapsToRemoteException() {
        server.enqueue(new MockResponse()
            .setBody("{\"resources\":[{\"uri\":\"not a uri ###\",\"name\":\"bad\"}]}")
            .setHeader("Content-Type", "application/json"));

        StepVerifier.create(client.listResources("test-server"))
            .expectErrorMatches(e -> e instanceof McpRemoteException
                && e.getMessage().contains("invalid URI"))
            .verify();
    }

    @Test
    void listResources_unmapped401() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("Unauthorized"));

        StepVerifier.create(client.listResources("test-server"))
            .expectErrorMatches(e -> e instanceof McpRemoteException
                && e.getMessage().contains("401"))
            .verify();
    }

    @Test
    void listResources_unmapped429() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("Too Many Requests"));

        StepVerifier.create(client.listResources("test-server"))
            .expectErrorMatches(e -> e instanceof McpRemoteException
                && e.getMessage().contains("429"))
            .verify();
    }

    @Test
    void readResource_happyPath() {
        server.enqueue(new MockResponse()
            .setBody("""
                {"contents":[{"uri":"file:///data.csv","mimeType":"text/csv","text":"a,b\\n1,2"}]}
                """)
            .setHeader("Content-Type", "application/json"));

        URI uri = URI.create("file:///data.csv");
        StepVerifier.create(client.readResource("test-server", uri))
            .assertNext(c -> {
                assertThat(c.uri()).isEqualTo(uri);
                assertThat(c.mimeType()).isEqualTo("text/csv");
                assertThat(c.text()).isEqualTo("a,b\n1,2");
            })
            .verifyComplete();
    }

    @Test
    void readResource_sendsUriInBody() throws InterruptedException {
        server.enqueue(new MockResponse()
            .setBody("{\"contents\":[{\"uri\":\"x\",\"text\":\"ok\"}]}")
            .setHeader("Content-Type", "application/json"));

        URI uri = URI.create("my://resource");
        StepVerifier.create(client.readResource("test-server", uri))
            .expectNextCount(1)
            .verifyComplete();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("my://resource");
    }

    @Test
    void readResource_unmapped500() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

        StepVerifier.create(client.readResource("test-server", URI.create("x")))
            .expectErrorMatches(e -> e instanceof McpRemoteException
                && e.getMessage().contains("500"))
            .verify();
    }

    @Test
    void readResource_invalidJson_mapsToRemoteException() {
        server.enqueue(new MockResponse()
            .setBody("not json")
            .setHeader("Content-Type", "application/json"));

        StepVerifier.create(client.readResource("test-server", URI.create("x")))
            .expectErrorMatches(e -> e instanceof McpRemoteException
                && e.getMessage().contains("failed to parse"))
            .verify();
    }

    @Test
    void unknownServer_throwsServerNotFound() {
        StepVerifier.create(client.listResources("no-such-server"))
            .expectErrorMatches(e -> e instanceof McpServerNotFoundException
                && e.getMessage().contains("no-such-server"))
            .verify();
    }

    @Test
    void listResources_noAuthWhenTokenEmpty() throws Exception {
        MockWebServer localServer = new MockWebServer();
        localServer.start();
        try {
            McpProperties.McpServerDefinition def =
                new McpProperties.McpServerDefinition(true, localServer.url("/").toString(), "");
            McpProperties props = new McpProperties(true, Map.of("bare", def));
            HttpMcpClient bareClient = new HttpMcpClient(props);

            localServer.enqueue(new MockResponse()
                .setBody("{\"resources\":[]}")
                .setHeader("Content-Type", "application/json"));

            StepVerifier.create(bareClient.listResources("bare"))
                .verifyComplete();

            RecordedRequest req = localServer.takeRequest();
            assertThat(req.getHeader("Authorization")).isNull();
        } finally {
            localServer.shutdown();
        }
    }

    @Test
    void readResource_contentKeyFallback() {
        server.enqueue(new MockResponse()
            .setBody("{\"content\":{\"uri\":\"file:///x\",\"text\":\"hello\"}}")
            .setHeader("Content-Type", "application/json"));

        StepVerifier.create(client.readResource("test-server", URI.create("file:///x")))
            .assertNext(c -> {
                assertThat(c.text()).isEqualTo("hello");
                assertThat(c.mimeType()).isEqualTo("text/plain");
            })
            .verifyComplete();
    }

    @Test
    void listResources_customHeadersFromConfig() throws Exception {
        MockWebServer localServer = new MockWebServer();
        localServer.start();
        try {
            McpProperties.McpServerDefinition def =
                new McpProperties.McpServerDefinition(true, McpTransportType.HTTP,
                    localServer.url("/").toString(), "tok",
                    null, null, null, null, 10_000,
                    Map.of("X-Api-Key", "key123", "X-Trace-Id", "abc"),
                    30_000, 300_000);
            McpProperties props = new McpProperties(true, Map.of("hdr", def));
            HttpMcpClient hdrClient = new HttpMcpClient(props);

            localServer.enqueue(new MockResponse()
                .setBody("{\"resources\":[]}")
                .setHeader("Content-Type", "application/json"));

            StepVerifier.create(hdrClient.listResources("hdr"))
                .verifyComplete();

            RecordedRequest req = localServer.takeRequest();
            assertThat(req.getHeader("Authorization")).isEqualTo("Bearer tok");
            assertThat(req.getHeader("X-Api-Key")).isEqualTo("key123");
            assertThat(req.getHeader("X-Trace-Id")).isEqualTo("abc");
        } finally {
            localServer.shutdown();
        }
    }

    @Test
    void disabledServer_returnsRemoteException() {
        McpProperties props = new McpProperties(true, Map.of(
            "off", new McpProperties.McpServerDefinition(false, "http://localhost:1", "")
        ));
        HttpMcpClient offClient = new HttpMcpClient(props);
        StepVerifier.create(offClient.listResources("off"))
            .expectErrorMatches(e -> e instanceof McpRemoteException mre
                && mre.getMessage().contains("server is disabled"))
            .verify();
    }

    @Test
    void blankBaseUrl_returnsRemoteException() {
        McpProperties props = new McpProperties(true, Map.of(
            "no-url", new McpProperties.McpServerDefinition(true, McpTransportType.HTTP,
                "", "", null, null, null, null, 10_000, null, 30_000, 300_000)
        ));
        HttpMcpClient noUrlClient = new HttpMcpClient(props);
        StepVerifier.create(noUrlClient.listResources("no-url"))
            .expectErrorMatches(e -> e instanceof McpRemoteException mre
                && mre.getMessage().contains("non-blank 'baseUrl'"))
            .verify();
    }
}
