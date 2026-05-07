package com.clawcode.agent.tools.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class HttpWebSearchClientTest {

    private final MockWebServer server = new MockWebServer();

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private HttpWebSearchClient createClient() {
        WebToolsProperties props = new WebToolsProperties(
            true, true, true,
            server.url("/").toString(),
            "test-api-key",
            5_000, 1_048_576, 50_000,
            List.of("http", "https"), List.of());
        return new HttpWebSearchClient(props);
    }

    @Test
    void successfulSearchReturnsResults() throws IOException, InterruptedException {
        server.start();
        server.enqueue(new MockResponse()
            .setBody("""
                {
                  "web": {
                    "results": [
                      {"title": "Result 1", "url": "https://example.com/1", "description": "Desc 1"},
                      {"title": "Result 2", "url": "https://example.com/2", "description": "Desc 2"}
                    ]
                  }
                }
                """)
            .setHeader("Content-Type", "application/json"));

        var client = createClient();

        StepVerifier.create(client.search("test query", 10).collectList())
            .assertNext(results -> {
                assertThat(results).hasSize(2);
                assertThat(results.get(0).title()).isEqualTo("Result 1");
                assertThat(results.get(0).url()).isEqualTo("https://example.com/1");
                assertThat(results.get(0).snippet()).isEqualTo("Desc 1");
                assertThat(results.get(0).source()).isEqualTo(server.url("/").toString());
            })
            .verifyComplete();

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("X-Subscription-Token")).isEqualTo("test-api-key");
        assertThat(request.getHeader("Accept")).isEqualTo("application/json");
        assertThat(request.getPath()).contains("q=test%20query").contains("count=10");
    }

    @Test
    void flatResultsFormatSupported() throws IOException {
        server.start();
        server.enqueue(new MockResponse()
            .setBody("""
                {
                  "results": [
                    {"title": "Flat", "url": "https://flat.com", "snippet": "Flat snippet"}
                  ]
                }
                """)
            .setHeader("Content-Type", "application/json"));

        var client = createClient();

        StepVerifier.create(client.search("flat", 5).collectList())
            .assertNext(results -> {
                assertThat(results).hasSize(1);
                assertThat(results.get(0).title()).isEqualTo("Flat");
                assertThat(results.get(0).snippet()).isEqualTo("Flat snippet");
            })
            .verifyComplete();
    }

    @Test
    void itemsWithoutUrlSkipped() throws IOException {
        server.start();
        server.enqueue(new MockResponse()
            .setBody("""
                {
                  "web": {
                    "results": [
                      {"title": "Has URL", "url": "https://ok.com", "description": "Ok"},
                      {"title": "No URL", "description": "Skipped"}
                    ]
                  }
                }
                """)
            .setHeader("Content-Type", "application/json"));

        var client = createClient();

        StepVerifier.create(client.search("test", 10).collectList())
            .assertNext(results -> assertThat(results).hasSize(1))
            .verifyComplete();
    }

    @Test
    void emptyResultsReturnsEmptyFlux() throws IOException {
        server.start();
        server.enqueue(new MockResponse()
            .setBody("{\"web\": {\"results\": []}}")
            .setHeader("Content-Type", "application/json"));

        var client = createClient();

        StepVerifier.create(client.search("obscure", 10).collectList())
            .assertNext(results -> assertThat(results).isEmpty())
            .verifyComplete();
    }

    @Test
    void missingResultsKeyReturnsEmptyFlux() throws IOException {
        server.start();
        server.enqueue(new MockResponse()
            .setBody("{\"query\": \"test\", \"total\": 0}")
            .setHeader("Content-Type", "application/json"));

        var client = createClient();

        StepVerifier.create(client.search("nothing", 10).collectList())
            .assertNext(results -> assertThat(results).isEmpty())
            .verifyComplete();
    }

    @Test
    void limitTruncatesResults() throws IOException {
        server.start();
        server.enqueue(new MockResponse()
            .setBody("""
                {
                  "web": {
                    "results": [
                      {"title": "R1", "url": "https://a.com/1", "description": "D1"},
                      {"title": "R2", "url": "https://a.com/2", "description": "D2"},
                      {"title": "R3", "url": "https://a.com/3", "description": "D3"}
                    ]
                  }
                }
                """)
            .setHeader("Content-Type", "application/json"));

        var client = createClient();

        StepVerifier.create(client.search("test", 2).collectList())
            .assertNext(results -> assertThat(results).hasSize(2))
            .verifyComplete();
    }

    @Test
    void non2xxReturnsError() throws IOException {
        server.start();
        server.enqueue(new MockResponse().setResponseCode(403));

        var client = createClient();

        StepVerifier.create(client.search("forbidden", 10).collectList())
            .expectError()
            .verify();
    }

    @Test
    void serverErrorReturnsError() throws IOException {
        server.start();
        server.enqueue(new MockResponse().setResponseCode(500));

        var client = createClient();

        StepVerifier.create(client.search("fail", 10).collectList())
            .expectError()
            .verify();
    }

    @Test
    void alternateFieldNamesSupported() throws IOException {
        server.start();
        server.enqueue(new MockResponse()
            .setBody("""
                {
                  "results": [
                    {"title": "Alt", "link": "https://alt.com", "description": "Alt desc"}
                  ]
                }
                """)
            .setHeader("Content-Type", "application/json"));

        var client = createClient();

        StepVerifier.create(client.search("alt", 10).collectList())
            .assertNext(results -> {
                assertThat(results).hasSize(1);
                assertThat(results.get(0).url()).isEqualTo("https://alt.com");
            })
            .verifyComplete();
    }
}
