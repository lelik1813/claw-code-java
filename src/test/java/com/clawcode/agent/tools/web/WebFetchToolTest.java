package com.clawcode.agent.tools.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class WebFetchToolTest {

    private static final WebToolsProperties PROPS = new WebToolsProperties(
        true, true, true, null, null, 5_000, 1_048_576, 100, List.of("http", "https"), List.of());

    private final MockWebServer server = new MockWebServer();

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private WebFetchTool permissiveTool() {
        WebUrlGuard permissive = new WebUrlGuard(PROPS) {
            @Override
            public URI validateAndNormalize(String raw) {
                return URI.create(raw).normalize();
            }
        };
        return new WebFetchTool(permissive, PROPS);
    }

    @Test
    void htmlParsedToText() throws IOException {
        server.start();
        server.enqueue(new MockResponse()
            .setBody("<html><body><h1>Title</h1><p>Hello world</p></body></html>")
            .setHeader("Content-Type", "text/html"));

        String url = server.url("/page").toString();

        StepVerifier.create(permissiveTool().execute(Map.of("url", url), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                Map<String, String> map = (Map<String, String>) result;
                assertThat(map.get("url")).isEqualTo(url);
                assertThat(map.get("text")).contains("Title").contains("Hello world");
                assertThat(map.get("text")).doesNotContain("<h1>").doesNotContain("<p>");
            })
            .verifyComplete();
    }

    @Test
    void whitespaceNormalized() throws IOException {
        server.start();
        server.enqueue(new MockResponse()
            .setBody("<div>  lots   \n\n  of   \t  spaces  </div>")
            .setHeader("Content-Type", "text/html"));

        String url = server.url("/ws").toString();

        StepVerifier.create(permissiveTool().execute(Map.of("url", url), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                Map<String, String> map = (Map<String, String>) result;
                assertThat(map.get("text")).isEqualTo("lots of spaces");
            })
            .verifyComplete();
    }

    @Test
    void longResponseTruncated() {
        WebToolsProperties shortProps = new WebToolsProperties(
            true, true, true, null, null, 5_000, 1_048_576, 20, List.of("http", "https"), List.of());
        WebFetchTool shortTool = new WebFetchTool(shortProps);

        String longText = "a".repeat(100);
        assertThat(shortTool.extractText("<p>" + longText + "</p>"))
            .startsWith("a")
            .endsWith("... [truncated]")
            .hasSize(20 + "... [truncated]".length());
    }

    @Test
    void emptyUrlReturnsError() {
        StepVerifier.create(permissiveTool().execute(Map.of("url", ""), null))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().equals("url must not be empty"))
            .verify();
    }

    @Test
    void missingUrlReturnsError() {
        StepVerifier.create(permissiveTool().execute(Map.of(), null))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().equals("url must not be empty"))
            .verify();
    }

    @Test
    void nullInputReturnsError() {
        StepVerifier.create(permissiveTool().execute(null, null))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().equals("url must not be empty"))
            .verify();
    }

    @Test
    void blockedUrlRejectedBeforeNetwork() {
        WebFetchTool strictTool = new WebFetchTool(PROPS);
        StepVerifier.create(
                Mono.defer(() -> strictTool.execute(Map.of("url", "https://127.0.0.1/secret"), null)))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().contains("Private/local"))
            .verify();
    }

    @Test
    void fileSchemeRejected() {
        WebFetchTool strictTool = new WebFetchTool(PROPS);
        StepVerifier.create(
                Mono.defer(() -> strictTool.execute(Map.of("url", "file:///etc/passwd"), null)))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().contains("Scheme not allowed"))
            .verify();
    }

    @Test
    void non200ResponseReturnsError() throws IOException {
        server.start();
        server.enqueue(new MockResponse().setResponseCode(500));

        String url = server.url("/error").toString();

        StepVerifier.create(permissiveTool().execute(Map.of("url", url), null))
            .expectError()
            .verify();
    }

    @Test
    void nameIsWebFetch() {
        assertThat(new WebFetchTool(PROPS).name()).isEqualTo("web_fetch");
    }
}
