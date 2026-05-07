package com.clawcode.agent.plugins;

import com.clawcode.agent.tools.Tool;
import com.clawcode.agent.tools.ToolDefinition;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultPluginToolFactoryTest {

    private final DefaultPluginToolFactory factory = new DefaultPluginToolFactory();

    @Test
    void createsHttpToolFromDescriptor() {
        var descriptor = new PluginToolDescriptor("fetch_data", "http",
            Map.of("url", "https://api.example.com/data", "method", "GET"));

        Optional<Tool> result = factory.tryCreate(descriptor);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("fetch_data");
    }

    @Test
    void httpsTypeCreatesHttpTool() {
        var descriptor = new PluginToolDescriptor("secure_fetch", "https",
            Map.of("url", "https://secure.example.com/api"));

        Optional<Tool> result = factory.tryCreate(descriptor);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("secure_fetch");
    }

    @Test
    void unknownTypeReturnsEmpty() {
        var descriptor = new PluginToolDescriptor("mystery", "grpc", Map.of());

        Optional<Tool> result = factory.tryCreate(descriptor);

        assertThat(result).isEmpty();
    }

    @Test
    void nullDescriptorReturnsEmpty() {
        assertThat(factory.tryCreate(null)).isEmpty();
    }

    @Test
    void httpToolWithoutUrlThrows() {
        var descriptor = new PluginToolDescriptor("broken", "http", Map.of());

        assertThatThrownBy(() -> factory.tryCreate(descriptor))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires non-blank 'url'");
    }

    @Test
    void httpToolWithBlankUrlThrows() {
        var descriptor = new PluginToolDescriptor("broken", "http",
            Map.of("url", "  "));

        assertThatThrownBy(() -> factory.tryCreate(descriptor))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires non-blank 'url'");
    }

    @Test
    void httpToolDefaultsMethodToGet() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        server.enqueue(new MockResponse().setBody("ok"));
        try {
            var descriptor = new PluginToolDescriptor("t", "http",
                Map.of("url", server.url("/").toString()));

            Tool tool = factory.tryCreate(descriptor).orElseThrow();

            StepVerifier.create(tool.execute("test", null))
                .expectNextMatches(r -> r instanceof String s && s.equals("ok"))
                .verifyComplete();

            assertThat(server.takeRequest().getMethod()).isEqualTo("GET");
        } finally {
            server.shutdown();
        }
    }

    @Test
    void httpToolPostMethod() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        server.enqueue(new MockResponse().setBody("posted"));
        try {
            var descriptor = new PluginToolDescriptor("poster", "http",
                Map.of("url", server.url("/submit").toString(), "method", "POST"));

            Tool tool = factory.tryCreate(descriptor).orElseThrow();

            StepVerifier.create(tool.execute("payload", null))
                .expectNextMatches(r -> r instanceof String s && s.equals("posted"))
                .verifyComplete();

            assertThat(server.takeRequest().getMethod()).isEqualTo("POST");
        } finally {
            server.shutdown();
        }
    }

    @Test
    void httpToolNetworkErrorReturnsControlledMessage() throws IOException {
        MockWebServer server = new MockWebServer();
        server.start();
        server.shutdown();

        var descriptor = new PluginToolDescriptor("flaky", "http",
            Map.of("url", "http://localhost:" + server.getPort() + "/fail",
                   "timeout", 500));

        Tool tool = factory.tryCreate(descriptor).orElseThrow();

        StepVerifier.create(tool.execute("test", null))
            .expectNextMatches(r -> r instanceof String s && s.startsWith("[plugin tool error:"))
            .verifyComplete();
    }

    @Test
    void httpToolCustomTimeoutFromConfig() throws IOException {
        MockWebServer server = new MockWebServer();
        server.start();
        server.enqueue(new MockResponse().setBody("fast"));
        try {
            var descriptor = new PluginToolDescriptor("t", "http",
                Map.of("url", server.url("/").toString(), "timeout", 1000));

            Tool tool = factory.tryCreate(descriptor).orElseThrow();

            StepVerifier.create(tool.execute("x", null))
                .expectNextMatches(r -> r instanceof String s && s.equals("fast"))
                .verifyComplete();
        } finally {
            server.shutdown();
        }
    }

    @Test
    void httpToolStringTimeoutParsedCorrectly() {
        var descriptor = new PluginToolDescriptor("t", "http",
            Map.of("url", "https://example.com", "timeout", "2000"));

        Optional<Tool> result = factory.tryCreate(descriptor);
        assertThat(result).isPresent();
    }

    @Test
    void createAllSkipsUnsupportedTypes() {
        var plugin = new PluginDescriptor("mixed", "Mixed", "1.0", null,
            List.of(
                new PluginToolDescriptor("good", "http",
                    Map.of("url", "https://api.example.com")),
                new PluginToolDescriptor("unknown", "grpc", Map.of())
            ));

        List<Tool> tools = factory.createAll(plugin);

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("good");
    }

    @Test
    void createAllReturnsEmptyForPluginWithNoTools() {
        var plugin = new PluginDescriptor("bare", "Bare", "1.0", null);

        List<Tool> tools = factory.createAll(plugin);

        assertThat(tools).isEmpty();
    }

    @Test
    void registerCustomTypeCreatesTool() {
        factory.registerType("lambda", desc ->
            new Tool() {
                @Override public String name() { return desc.name(); }
                @Override public ToolDefinition definition() { return new ToolDefinition(name(), "test tool", Map.of()); }
                @Override public Mono<Object> execute(Object input, Object ctx) {
                    return Mono.just("lambda-result");
                }
            });

        var descriptor = new PluginToolDescriptor("custom", "lambda", Map.of());

        Optional<Tool> result = factory.tryCreate(descriptor);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("custom");

        StepVerifier.create(result.get().execute("in", null))
            .expectNext("lambda-result")
            .verifyComplete();
    }
}
