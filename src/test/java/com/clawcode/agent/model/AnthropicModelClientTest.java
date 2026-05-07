package com.clawcode.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.AssistantTextBlock;
import com.clawcode.agent.shared.message.AssistantThinkingBlock;
import com.clawcode.agent.shared.message.AssistantToolUseBlock;
import com.clawcode.agent.shared.message.ToolResultMessage;
import com.clawcode.agent.shared.message.UserMessage;
import com.clawcode.agent.tools.Tool;
import com.clawcode.agent.tools.file.FileEditTool;
import com.clawcode.agent.tools.file.FileGlobTool;
import com.clawcode.agent.tools.file.FileListTool;
import com.clawcode.agent.tools.file.FileReadTool;
import com.clawcode.agent.tools.file.FileReadStateStore;
import com.clawcode.agent.tools.file.FileSearchTool;
import com.clawcode.agent.tools.file.FileWriteTool;
import com.clawcode.agent.tools.shell.PowerShellTool;
import com.clawcode.agent.tools.shell.PowerShellToolProperties;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicModelClientTest {

    private MockWebServer server;
    private AnthropicModelClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        AnthropicProperties properties = new AnthropicProperties(
            "test-token",
            server.url("/").toString(),
            5000,
            "deepseek-v4-flash"
        );
        client = new AnthropicModelClient(properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void sendsCorrectHttpRequest() throws InterruptedException {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
            "default",
            "You are helpful"
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/v1/messages");
        assertThat(recorded.getHeader("x-api-key")).isEqualTo("test-token");
        assertThat(recorded.getHeader("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(recorded.getHeader("Content-Type")).contains("application/json");

        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"model\":\"deepseek-v4-flash\"");
        assertThat(body).contains("\"stream\":true");
        assertThat(body).contains("\"system\":\"You are helpful\"");
        assertThat(body).contains("\"role\":\"user\"");
        assertThat(body).contains("\"content\":\"hello\"");
    }

    @Test
    void nullSystemPromptIsExcludedFromBody() throws InterruptedException {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
            "glm-5.1",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        RecordedRequest recorded = server.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(body).doesNotContain("system");
    }

    @Test
    void toolsAreIncludedInRequestBodyWhenProvided() throws InterruptedException {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "write file")),
            "glm-5.1",
            "Use tools when helpful",
            List.of(new ModelToolDefinition(
                "file_write",
                "Write UTF-8 text content to a file.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "path", Map.of("type", "string"),
                        "content", Map.of("type", "string")
                    ),
                    "required", List.of("path", "content")
                )
            ))
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        RecordedRequest recorded = server.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"tools\"");
        assertThat(body).contains("\"name\":\"file_write\"");
        assertThat(body).contains("\"input_schema\"");
    }

    @Test
    void happyPathEmitsStartedDeltaCompleted() {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
            "glm-5.1",
            null
        );

        StepVerifier.create(client.stream(request))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent s
                && s.model().equals("glm-5.1"))
            .expectNextMatches(e -> e instanceof ModelTextDeltaEvent d
                && d.text().equals("Hello"))
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    @Test
    void doneSentinelIsIgnored() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"glm-5.1"}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"text":"Hello"}}

            event: message_stop
            data: {"type":"message_stop"}

            data: [DONE]

            """;

        server.enqueue(sseResponse(sse));

        StepVerifier.create(client.stream(defaultRequest()))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent)
            .expectNextMatches(e -> e instanceof ModelTextDeltaEvent d
                && d.text().equals("Hello"))
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    @Test
    void deepSeekThinkingBlocksAreCapturedWithoutTextDeltaWhileTextStillStreams() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"deepseek-v4-flash"}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"internal reasoning"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hello!"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":54}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

        server.enqueue(sseResponse(sse));

        StepVerifier.create(client.stream(defaultRequest()))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent s
                && s.model().equals("deepseek-v4-flash"))
            .expectNextMatches(e -> e instanceof ModelThinkingBlockEvent thinking
                && thinking.block().thinking().equals("internal reasoning")
                && thinking.block().signature().equals("sig"))
            .expectNextMatches(e -> e instanceof ModelTextDeltaEvent d
                && d.text().equals("Hello!"))
            .expectNextMatches(e -> e instanceof ModelStopReasonEvent stop
                && stop.stopReason().equals("end_turn"))
            .expectNextMatches(e -> e instanceof ModelUsageEvent usage
                && usage.outputTokens() == 54)
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    @Test
    void multipleDeltasAreEmittedInOrder() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"glm-5.1"}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"text":"Hello"}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"text":" world"}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"text":"!"}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

        server.enqueue(sseResponse(sse));

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
            "glm-5.1",
            null
        );

        StepVerifier.create(client.stream(request))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent)
            .expectNextMatches(e -> e instanceof ModelTextDeltaEvent d
                && d.text().equals("Hello"))
            .expectNextMatches(e -> e instanceof ModelTextDeltaEvent d
                && d.text().equals(" world"))
            .expectNextMatches(e -> e instanceof ModelTextDeltaEvent d
                && d.text().equals("!"))
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    @Test
    void httpErrorPropagatesAsOnError() {
        server.enqueue(new MockResponse()
            .setResponseCode(429)
            .setBody("{\"error\":{\"message\":\"rate limited\"}}"));

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
            "glm-5.1",
            null
        );

        StepVerifier.create(client.stream(request))
            .expectErrorMatches(e -> e instanceof RuntimeException
                && e.getMessage().contains("Anthropic API error")
                && e.getMessage().contains("429"))
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void retriesWhenConnectionClosesBeforeResponse() {
        server.enqueue(new MockResponse()
            .setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        enqueueHappyPath();

        StepVerifier.create(client.stream(defaultRequest()))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent)
            .expectNextMatches(e -> e instanceof ModelTextDeltaEvent d
                && d.text().equals("Hello"))
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();

        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void streamErrorEventEmitsModelErrorEvent() {
        String sse = """
            event: error
            data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

            """;

        server.enqueue(sseResponse(sse));

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
            "glm-5.1",
            null
        );

        StepVerifier.create(client.stream(request))
            .expectNextMatches(e -> e instanceof ModelErrorEvent err
                && err.message().equals("Overloaded")
                && err.providerCode().equals("overloaded_error"))
            .verifyComplete();
    }

    @Test
    void messageDeltaEmitsStopReasonAndUsage() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"glm-5.1"}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"text":"Hi"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

        server.enqueue(sseResponse(sse));

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
            "glm-5.1",
            null
        );

        StepVerifier.create(client.stream(request))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent)
            .expectNextMatches(e -> e instanceof ModelTextDeltaEvent d && d.text().equals("Hi"))
            .expectNextMatches(e -> e instanceof ModelStopReasonEvent s && s.stopReason().equals("end_turn"))
            .expectNextMatches(e -> e instanceof ModelUsageEvent u && u.outputTokens().equals(42L))
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    @Test
    void messageDeltaWithoutStopReasonEmitsOnlyUsage() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"glm-5.1"}}

            event: message_delta
            data: {"type":"message_delta","delta":{},"usage":{"output_tokens":15}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

        server.enqueue(sseResponse(sse));

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
            "glm-5.1",
            null
        );

        StepVerifier.create(client.stream(request))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent)
            .expectNextMatches(e -> e instanceof ModelUsageEvent u && u.outputTokens().equals(15L))
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    @Test
    void streamErrorEventWithoutProviderCodeEmitsModelErrorEvent() {
        String sse = """
            event: error
            data: {"type":"error","error":{"message":"something broke"}}

            """;

        server.enqueue(sseResponse(sse));

        var request = defaultRequest();

        StepVerifier.create(client.stream(request))
            .expectNextMatches(e -> e instanceof ModelErrorEvent err
                && err.message().equals("something broke")
                && err.providerCode() == null)
            .verifyComplete();
    }

    @Test
    void messageDeltaWithOnlyStopReasonEmitsNoUsage() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"glm-5.1"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

        server.enqueue(sseResponse(sse));

        StepVerifier.create(client.stream(defaultRequest()))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent)
            .expectNextMatches(e -> e instanceof ModelStopReasonEvent s
                && s.stopReason().equals("max_tokens"))
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    @Test
    void contentBlockDeltaWithEmptyTextEmitsNothing() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"glm-5.1"}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"text":""}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

        server.enqueue(sseResponse(sse));

        StepVerifier.create(client.stream(defaultRequest()))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent)
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    @Test
    void unknownSseTypeIsSilentlyDropped() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"glm-5.1"}}

            event: ping
            data: {"type":"ping"}

            event: message_stop
            data: {"type":"message_stop"}

            """;

        server.enqueue(sseResponse(sse));

        StepVerifier.create(client.stream(defaultRequest()))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent)
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    @Test
    void resolvesDefaultModelToConfiguredValue() throws InterruptedException {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
            "default",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        RecordedRequest recorded = server.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"model\":\"deepseek-v4-flash\"");
    }

    @Test
    void toolResultMessageIsMappedToToolResultBlock() throws InterruptedException {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(
                new UserMessage(UUID.randomUUID(), Instant.now(), "hi"),
                new ToolResultMessage(UUID.randomUUID(), Instant.now(),
                    "toolu_abc", "get_weather", "Paris, 22C", false)
            ),
            "glm-5.1",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        RecordedRequest recorded = server.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"role\":\"user\",\"content\":[");
        assertThat(body).contains("\"type\":\"tool_result\"");
        assertThat(body).contains("\"tool_use_id\":\"toolu_abc\"");
        assertThat(body).contains("\"content\":\"Paris, 22C\"");
        assertThat(body).doesNotContain("\"is_error\"");
    }

    @Test
    void toolResultErrorMessageIncludesIsErrorFlag() throws InterruptedException {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(
                new ToolResultMessage(UUID.randomUUID(), Instant.now(),
                    "toolu_x", "fail_tool", "timeout", true)
            ),
            "glm-5.1",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        RecordedRequest recorded = server.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"type\":\"tool_result\"");
        assertThat(body).contains("\"tool_use_id\":\"toolu_x\"");
        assertThat(body).contains("\"is_error\":true");
    }

    @Test
    void assistantMessagesAreMappedCorrectly() throws InterruptedException {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(
                new UserMessage(UUID.randomUUID(), Instant.now(), "hi"),
                new AssistantMessage(UUID.randomUUID(), Instant.now(), "hello!"),
                new UserMessage(UUID.randomUUID(), Instant.now(), "how are you?")
            ),
            "glm-5.1",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        RecordedRequest recorded = server.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"role\":\"user\",\"content\":\"hi\"");
        assertThat(body).contains("\"role\":\"assistant\",\"content\":\"hello!\"");
        assertThat(body).contains("\"role\":\"user\",\"content\":\"how are you?\"");
    }

    @Test
    void toolUseBlockEmitsModelToolUseEvent() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"glm-5.1"}}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_abc","name":"get_weather"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"city\\":"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\\"Paris\\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":50}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

        server.enqueue(sseResponse(sse));

        StepVerifier.create(client.stream(defaultRequest()))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent)
            .expectNextMatches(e -> e instanceof ModelToolUseEvent tu
                && tu.toolCallId().equals("toolu_abc")
                && tu.toolName().equals("get_weather"))
            .expectNextMatches(e -> e instanceof ModelStopReasonEvent s
                && s.stopReason().equals("tool_use"))
            .expectNextMatches(e -> e instanceof ModelUsageEvent u
                && u.outputTokens().equals(50L))
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    @Test
    void toolUseBlockWithEmptyInputEmitsMapOf() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"glm-5.1"}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_123","name":"list_files"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_stop
            data: {"type":"message_stop"}

            """;

        server.enqueue(sseResponse(sse));

        StepVerifier.create(client.stream(defaultRequest()))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent)
            .expectNextMatches(e -> e instanceof ModelToolUseEvent tu
                && tu.toolCallId().equals("toolu_123")
                && tu.toolName().equals("list_files")
                && tu.input().equals(java.util.Map.of()))
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    @Test
    void toolUseBlockWithMalformedJsonFallsBackToRawString() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"glm-5.1"}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_bad","name":"broken"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{invalid"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_stop
            data: {"type":"message_stop"}

            """;

        server.enqueue(sseResponse(sse));

        StepVerifier.create(client.stream(defaultRequest()))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent)
            .expectNextMatches(e -> e instanceof ModelToolUseEvent tu
                && tu.toolCallId().equals("toolu_bad")
                && tu.toolName().equals("broken")
                && tu.input() instanceof String
                && ((String) tu.input()).equals("{invalid"))
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    @Test
    void toolUseDeltaWithoutStartIsIgnored() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"glm-5.1"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":5,"delta":{"type":"input_json_delta","partial_json":"{\\"orphan\\""}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

        server.enqueue(sseResponse(sse));

        StepVerifier.create(client.stream(defaultRequest()))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent)
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    @Test
    void contentBlockStopWithoutStartIsIgnored() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"glm-5.1"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":9}

            event: message_stop
            data: {"type":"message_stop"}

            """;

        server.enqueue(sseResponse(sse));

        StepVerifier.create(client.stream(defaultRequest()))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent)
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    @Test
    void mixedTextAndToolUseBlocksInOrder() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"glm-5.1"}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"I will check"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"search"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"q\\":\\"test\\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: message_stop
            data: {"type":"message_stop"}

            """;

        server.enqueue(sseResponse(sse));

        StepVerifier.create(client.stream(defaultRequest()))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent)
            .expectNextMatches(e -> e instanceof ModelTextDeltaEvent d
                && d.text().equals("I will check"))
            .expectNextMatches(e -> e instanceof ModelToolUseEvent tu
                && tu.toolCallId().equals("toolu_1")
                && tu.toolName().equals("search"))
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    @Test
    void textOnlyAssistantSendsContentAsString() throws InterruptedException {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(
                new UserMessage(UUID.randomUUID(), Instant.now(), "hi"),
                new AssistantMessage(UUID.randomUUID(), Instant.now(), "hello!"),
                new UserMessage(UUID.randomUUID(), Instant.now(), "ok")
            ),
            "glm-5.1",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        RecordedRequest recorded = server.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"role\":\"assistant\",\"content\":\"hello!\"");
        assertThat(body).doesNotContain("\"type\":\"text\"");
    }

    @Test
    void mixedAssistantBlocksSerializedAsArray() throws InterruptedException {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(
                new UserMessage(UUID.randomUUID(), Instant.now(), "search"),
                new AssistantMessage(UUID.randomUUID(), Instant.now(), List.of(
                    new AssistantTextBlock("let me look"),
                    new AssistantToolUseBlock("toolu_1", "search", Map.of("q", "test")),
                    new AssistantTextBlock(" found it")
                )),
                new ToolResultMessage(UUID.randomUUID(), Instant.now(),
                    "toolu_1", "search", "result data", false)
            ),
            "glm-5.1",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        RecordedRequest recorded = server.takeRequest();
        String body = recorded.getBody().readUtf8();

        // assistant content is an array of blocks
        assertThat(body).contains("\"role\":\"assistant\",\"content\":[");
        assertThat(body).contains("\"type\":\"text\"");
        assertThat(body).contains("\"text\":\"let me look\"");
        assertThat(body).contains("\"type\":\"tool_use\"");
        assertThat(body).contains("\"id\":\"toolu_1\"");
        assertThat(body).contains("\"name\":\"search\"");
        assertThat(body).contains("\"input\":{\"q\":\"test\"}");
        assertThat(body).contains("\"text\":\" found it\"");

        // tool_result still works
        assertThat(body).contains("\"type\":\"tool_result\"");
        assertThat(body).contains("\"tool_use_id\":\"toolu_1\"");
    }

    @Test
    void thinkingToolUseHistorySerializesThinkingBackToProvider() throws Exception {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(
                new UserMessage(UUID.randomUUID(), Instant.now(), "list files"),
                new AssistantMessage(UUID.randomUUID(), Instant.now(), List.of(
                    new AssistantThinkingBlock("internal reasoning", "sig-1"),
                    new AssistantToolUseBlock("toolu_1", "file_list", Map.of("path", "."))
                )),
                new ToolResultMessage(UUID.randomUUID(), Instant.now(),
                    "toolu_1", "file_list", "README.md", false)
            ),
            "deepseek-v4-flash",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        String body = server.takeRequest().getBody().readUtf8();
        JsonNode messages = new ObjectMapper().readTree(body).get("messages");
        JsonNode assistantContent = messages.get(1).get("content");

        assertThat(assistantContent.isArray()).isTrue();
        assertThat(assistantContent).hasSize(2);
        assertThat(assistantContent.get(0).get("type").asText()).isEqualTo("thinking");
        assertThat(assistantContent.get(0).get("thinking").asText()).isEqualTo("internal reasoning");
        assertThat(assistantContent.get(0).get("signature").asText()).isEqualTo("sig-1");
        assertThat(assistantContent.get(1).get("type").asText()).isEqualTo("tool_use");
        assertThat(assistantContent.get(1).get("id").asText()).isEqualTo("toolu_1");
        assertThat(assistantContent.get(1).get("name").asText()).isEqualTo("file_list");
        assertThat(messages.get(2).get("content").get(0).get("type").asText()).isEqualTo("tool_result");
    }

    @Test
    void persistedToolUseToolResultHistoryMapsToAnthropicWithoutResultLeakage() throws Exception {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(
                new UserMessage(UUID.randomUUID(), Instant.now(), "inspect src"),
                new AssistantMessage(UUID.randomUUID(), Instant.now(), List.of(
                    new AssistantTextBlock("I will inspect the file."),
                    new AssistantToolUseBlock("toolu_read_1", "file_read",
                        Map.of("path", "src/App.java"))
                )),
                new ToolResultMessage(UUID.randomUUID(), Instant.now(),
                    "toolu_read_1", "file_read", "class App {}", false),
                new AssistantMessage(UUID.randomUUID(), Instant.now(), "Final answer")
            ),
            "glm-5.1",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        String body = server.takeRequest().getBody().readUtf8();
        JsonNode messages = new ObjectMapper().readTree(body).get("messages");

        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(0).get("content").asText()).isEqualTo("inspect src");

        JsonNode assistantToolUse = messages.get(1);
        assertThat(assistantToolUse.get("role").asText()).isEqualTo("assistant");
        assertThat(assistantToolUse.get("content").isArray()).isTrue();
        assertThat(assistantToolUse.get("content")).hasSize(2);
        assertThat(assistantToolUse.get("content").get(0).get("type").asText()).isEqualTo("text");
        assertThat(assistantToolUse.get("content").get(0).get("text").asText())
            .isEqualTo("I will inspect the file.");
        assertThat(assistantToolUse.get("content").get(1).get("type").asText()).isEqualTo("tool_use");
        assertThat(assistantToolUse.get("content").get(1).get("id").asText()).isEqualTo("toolu_read_1");
        assertThat(assistantToolUse.get("content").get(1).get("name").asText()).isEqualTo("file_read");
        assertThat(assistantToolUse.get("content").get(1).get("input").get("path").asText())
            .isEqualTo("src/App.java");

        JsonNode toolResult = messages.get(2);
        assertThat(toolResult.get("role").asText()).isEqualTo("user");
        assertThat(toolResult.get("content").isArray()).isTrue();
        assertThat(toolResult.get("content")).hasSize(1);
        assertThat(toolResult.get("content").get(0).get("type").asText()).isEqualTo("tool_result");
        assertThat(toolResult.get("content").get(0).get("tool_use_id").asText()).isEqualTo("toolu_read_1");
        assertThat(toolResult.get("content").get(0).get("content").asText()).isEqualTo("class App {}");

        JsonNode finalAssistant = messages.get(3);
        assertThat(finalAssistant.get("role").asText()).isEqualTo("assistant");
        assertThat(finalAssistant.get("content").isTextual()).isTrue();
        assertThat(finalAssistant.get("content").asText()).isEqualTo("Final answer");

        assertThat(countAssistantTextMessages(messages, "Final answer")).isEqualTo(1);
        assertThat(body).doesNotContain("\"type\":\"result\"");
        assertThat(body).doesNotContain("\"stop_reason\"");
        assertThat(body).doesNotContain("\"duration_ms\"");
        assertThat(body).doesNotContain("\"permission_denials\"");
    }

    @Test
    void orphanToolResultsFromLegacyBlankAssistantRowsAreNotSentToProvider() throws Exception {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(
                new UserMessage(UUID.randomUUID(), Instant.now(), "first turn"),
                new AssistantMessage(UUID.randomUUID(), Instant.now(), ""),
                new ToolResultMessage(UUID.randomUUID(), Instant.now(),
                    "toolu_missing", "file_list", "README.md", false),
                new AssistantMessage(UUID.randomUUID(), Instant.now(), "First answer"),
                new UserMessage(UUID.randomUUID(), Instant.now(), "second turn")
            ),
            "glm-5.1",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        String body = server.takeRequest().getBody().readUtf8();
        JsonNode messages = new ObjectMapper().readTree(body).get("messages");

        assertThat(body).doesNotContain("\"type\":\"tool_result\"");
        assertThat(body).doesNotContain("toolu_missing");
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(1).get("content").asText()).isEqualTo("First answer");
        assertThat(messages.get(2).get("content").asText()).isEqualTo("second turn");
    }

    @Test
    void hookContextMessagesSerializeAsNormalMessagesWithoutLifecycleLeakage() throws Exception {
        enqueueHappyPath();

        var hookContext = new UserMessage(
            UUID.randomUUID(), Instant.now(), "hook context attachment");
        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(
                new UserMessage(UUID.randomUUID(), Instant.now(), "inspect src"),
                new AssistantMessage(UUID.randomUUID(), Instant.now(), List.of(
                    new AssistantToolUseBlock("toolu_read_1", "file_read",
                        Map.of("path", "src/App.java"))
                )),
                new ToolResultMessage(UUID.randomUUID(), Instant.now(),
                    "toolu_read_1", "file_read", "class App {}", false),
                hookContext,
                new AssistantMessage(UUID.randomUUID(), Instant.now(), "Final answer")
            ),
            "glm-5.1",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        String body = server.takeRequest().getBody().readUtf8();
        JsonNode messages = new ObjectMapper().readTree(body).get("messages");

        assertThat(messages).hasSize(5);
        assertThat(messages.get(3).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(3).get("content").asText()).isEqualTo("hook context attachment");
        assertThat(messages.get(4).get("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(4).get("content").asText()).isEqualTo("Final answer");

        assertThat(body).contains("hook context attachment");
        assertThat(body).doesNotContain("ToolPreHookResult");
        assertThat(body).doesNotContain("ToolPostHookResult");
        assertThat(body).doesNotContain("ToolPermissionDeniedHookResult");
        assertThat(body).doesNotContain("ToolStopHookResult");
        assertThat(body).doesNotContain("ToolHookPipeline");
        assertThat(body).doesNotContain("QueryTranscriptUpdateEvent");
        assertThat(body).doesNotContain("ToolPreHookContext");
        assertThat(body).doesNotContain("ToolPostHookContext");
        assertThat(body).doesNotContain("ToolPermissionDeniedHookContext");
        assertThat(body).doesNotContain("ToolStopHookContext");
        assertThat(body).doesNotContain("\"contextMessages\"");
        assertThat(body).doesNotContain("\"decision\"");
        assertThat(body).doesNotContain("\"denyReason\"");
        assertThat(body).doesNotContain("\"overrideReason\"");
    }

    @Test
    void persistedBudgetedToolResultAndSummaryStayCompactInAnthropicPayload() throws Exception {
        enqueueHappyPath();

        String omittedRawMiddle = "RAW-MIDDLE-SHOULD-NOT-LEAK";
        String compactedToolResult = """
            [tool result compacted]
            tool: file_read
            tool_call_id: toolu_read_large
            path: src/Large.java
            original_chars: 24000
            shown_chars: 4000
            omitted_chars: 20000

            HEAD excerpt
            [... omitted middle ...]
            TAIL excerpt
            """;
        String summary = """
            [tool batch summary]
            round: 0
            total_tool_calls: 1
            compacted_results: 1
            error_results: 0
            paths: src/Large.java
            """;

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(
                new UserMessage(UUID.randomUUID(), Instant.now(), "inspect large file"),
                new AssistantMessage(UUID.randomUUID(), Instant.now(), List.of(
                    new AssistantToolUseBlock("toolu_read_large", "file_read",
                        Map.of("path", "src/Large.java"))
                )),
                new ToolResultMessage(UUID.randomUUID(), Instant.now(),
                    "toolu_read_large", "file_read", compactedToolResult, false),
                new UserMessage(UUID.randomUUID(), Instant.now(), summary),
                new AssistantMessage(UUID.randomUUID(), Instant.now(), "Final answer after compacted read")
            ),
            "glm-5.1",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        String body = server.takeRequest().getBody().readUtf8();
        JsonNode messages = new ObjectMapper().readTree(body).get("messages");

        assertThat(messages).hasSize(5);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(2).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(3).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(4).get("role").asText()).isEqualTo("assistant");

        JsonNode assistantToolUse = messages.get(1).get("content").get(0);
        assertThat(assistantToolUse.get("type").asText()).isEqualTo("tool_use");
        assertThat(assistantToolUse.get("id").asText()).isEqualTo("toolu_read_large");
        assertThat(assistantToolUse.get("name").asText()).isEqualTo("file_read");
        assertThat(assistantToolUse.get("input").get("path").asText()).isEqualTo("src/Large.java");

        JsonNode toolResult = messages.get(2).get("content").get(0);
        assertThat(toolResult.get("type").asText()).isEqualTo("tool_result");
        assertThat(toolResult.get("tool_use_id").asText()).isEqualTo("toolu_read_large");
        assertThat(toolResult.get("content").asText())
            .contains("[tool result compacted]")
            .contains("path: src/Large.java")
            .contains("original_chars: 24000")
            .contains("shown_chars: 4000")
            .contains("omitted_chars: 20000")
            .contains("HEAD excerpt")
            .contains("TAIL excerpt")
            .doesNotContain(omittedRawMiddle);

        assertThat(messages.get(3).get("content").asText())
            .contains("[tool batch summary]")
            .contains("total_tool_calls: 1")
            .contains("compacted_results: 1")
            .contains("error_results: 0")
            .contains("paths: src/Large.java")
            .doesNotContain(omittedRawMiddle);

        assertThat(messages.get(4).get("content").asText())
            .isEqualTo("Final answer after compacted read");
        assertThat(countAssistantTextMessages(messages, "Final answer after compacted read")).isEqualTo(1);
        assertThat(body).doesNotContain(omittedRawMiddle);
        assertThat(body).doesNotContain("\"type\":\"result\"");
        assertThat(body).doesNotContain("\"tool_use_summary\"");
        assertThat(body).doesNotContain("\"duration_ms\"");
    }

    @Test
    void compactSummaryAndRecoveryMetaSerializeAsUserMessagesWithoutInternalLeakage() throws Exception {
        enqueueHappyPath();

        String rawOversizedMarker = "RAW_OVERSIZED_MARKER_SHOULD_NOT_REACH_PROVIDER";
        String compactSummary = """
            [conversation compacted]
            omitted_messages: 12
            omitted_user_messages: 4
            omitted_assistant_messages: 5
            omitted_tool_result_messages: 3
            omitted_tool_error_messages: 1
            first_user: first prompt
            last_assistant: last compacted answer
            """;
        String resumeMeta = """
            [continuation request]
            The previous response stopped because max_output_tokens was reached. Resume directly from the exact point where you stopped. Do not restart or repeat completed text.
            """;

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(
                new UserMessage(UUID.randomUUID(), Instant.now(), compactSummary),
                new UserMessage(UUID.randomUUID(), Instant.now(), "current user request"),
                new AssistantMessage(UUID.randomUUID(), Instant.now(), "partial answer "),
                new UserMessage(UUID.randomUUID(), Instant.now(), resumeMeta)
            ),
            "glm-5.1",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        String body = server.takeRequest().getBody().readUtf8();
        JsonNode messages = new ObjectMapper().readTree(body).get("messages");

        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(0).get("content").asText())
            .contains("[conversation compacted]")
            .contains("omitted_messages: 12")
            .doesNotContain(rawOversizedMarker);
        assertThat(messages.get(1).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).get("content").asText()).isEqualTo("current user request");
        assertThat(messages.get(2).get("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(2).get("content").asText()).isEqualTo("partial answer ");
        assertThat(messages.get(3).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(3).get("content").asText())
            .contains("[continuation request]")
            .contains("Resume directly from the exact point where you stopped");

        assertThat(body).doesNotContain(rawOversizedMarker);
        assertThat(body).doesNotContain("QueryResultEvent");
        assertThat(body).doesNotContain("QueryTranscriptUpdateEvent");
        assertThat(body).doesNotContain("QueryTextDeltaEvent");
        assertThat(body).doesNotContain("\"type\":\"result\"");
        assertThat(body).doesNotContain("\"tool_use_summary\"");
        assertThat(body).doesNotContain("\"stop_reason\"");
        assertThat(body).doesNotContain("\"duration_ms\"");
        assertThat(body).doesNotContain("\"permission_denials\"");
    }

    @Test
    void toolUseOnlyAssistantSerializedAsArray() throws InterruptedException {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(
                new UserMessage(UUID.randomUUID(), Instant.now(), "run it"),
                new AssistantMessage(UUID.randomUUID(), Instant.now(), List.of(
                    new AssistantToolUseBlock("toolu_99", "execute", Map.of("cmd", "ls"))
                ))
            ),
            "glm-5.1",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        RecordedRequest recorded = server.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"role\":\"assistant\",\"content\":[");
        assertThat(body).contains("\"type\":\"tool_use\"");
        assertThat(body).contains("\"id\":\"toolu_99\"");
        assertThat(body).contains("\"name\":\"execute\"");
    }

    @Test
    void toolUseEventCarriesContentIndex() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"glm-5.1"}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"thinking"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_7","name":"calc"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"x\\":1}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: message_stop
            data: {"type":"message_stop"}

            """;

        server.enqueue(sseResponse(sse));

        StepVerifier.create(client.stream(defaultRequest()))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent)
            .expectNextMatches(e -> e instanceof ModelTextDeltaEvent d
                && d.text().equals("thinking"))
            .expectNextMatches(e -> e instanceof ModelToolUseEvent tu
                && tu.toolCallId().equals("toolu_7")
                && tu.toolName().equals("calc")
                && tu.contentIndex() == 1)
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    // ── Wire-format: JSON body structure ────────────────────────────────

    @Test
    void requestBodyParsesAsValidJson() throws Exception {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hi")),
            "glm-5.1",
            "sys"
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        String body = server.takeRequest().getBody().readUtf8();
        JsonNode root = new ObjectMapper().readTree(body);

        assertThat(root.get("model").asText()).isEqualTo("glm-5.1");
        assertThat(root.get("max_tokens").asInt()).isEqualTo(4096);
        assertThat(root.get("stream").asBoolean()).isTrue();
        assertThat(root.get("system").asText()).isEqualTo("sys");

        JsonNode messages = root.get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(0).get("content").asText()).isEqualTo("hi");
    }

    @Test
    void assistantTextBodyHasStringContentNotArray() throws Exception {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(
                new UserMessage(UUID.randomUUID(), Instant.now(), "go"),
                new AssistantMessage(UUID.randomUUID(), Instant.now(), "reply")
            ),
            "glm-5.1",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        String body = server.takeRequest().getBody().readUtf8();
        JsonNode messages = new ObjectMapper().readTree(body).get("messages");
        JsonNode assistant = messages.get(1);

        assertThat(assistant.get("role").asText()).isEqualTo("assistant");
        assertThat(assistant.get("content").isTextual()).isTrue();
        assertThat(assistant.get("content").asText()).isEqualTo("reply");
    }

    @Test
    void assistantToolUseBodyHasCorrectBlockStructure() throws Exception {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(
                new UserMessage(UUID.randomUUID(), Instant.now(), "go"),
                new AssistantMessage(UUID.randomUUID(), Instant.now(), List.of(
                    new AssistantTextBlock("checking"),
                    new AssistantToolUseBlock("toolu_42", "read_file", Map.of("path", "/tmp/a.txt"))
                ))
            ),
            "glm-5.1",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        String body = server.takeRequest().getBody().readUtf8();
        JsonNode messages = new ObjectMapper().readTree(body).get("messages");
        JsonNode assistant = messages.get(1);

        assertThat(assistant.get("role").asText()).isEqualTo("assistant");
        assertThat(assistant.get("content").isArray()).isTrue();
        assertThat(assistant.get("content")).hasSize(2);

        JsonNode textBlock = assistant.get("content").get(0);
        assertThat(textBlock.get("type").asText()).isEqualTo("text");
        assertThat(textBlock.get("text").asText()).isEqualTo("checking");

        JsonNode toolBlock = assistant.get("content").get(1);
        assertThat(toolBlock.get("type").asText()).isEqualTo("tool_use");
        assertThat(toolBlock.get("id").asText()).isEqualTo("toolu_42");
        assertThat(toolBlock.get("name").asText()).isEqualTo("read_file");
        assertThat(toolBlock.get("input").get("path").asText()).isEqualTo("/tmp/a.txt");
    }

    @Test
    void toolResultBodyHasCorrectStructure() throws Exception {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(
                new ToolResultMessage(UUID.randomUUID(), Instant.now(),
                    "toolu_abc", "get_weather", "Paris, 22C", false)
            ),
            "glm-5.1",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        String body = server.takeRequest().getBody().readUtf8();
        JsonNode messages = new ObjectMapper().readTree(body).get("messages");
        JsonNode userMsg = messages.get(0);

        assertThat(userMsg.get("role").asText()).isEqualTo("user");
        assertThat(userMsg.get("content").isArray()).isTrue();

        JsonNode block = userMsg.get("content").get(0);
        assertThat(block.get("type").asText()).isEqualTo("tool_result");
        assertThat(block.get("tool_use_id").asText()).isEqualTo("toolu_abc");
        assertThat(block.get("content").asText()).isEqualTo("Paris, 22C");
        assertThat(block.has("is_error")).isFalse();
    }

    @Test
    void toolResultWithNullContentSendsEmptyString() throws Exception {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(
                new ToolResultMessage(UUID.randomUUID(), Instant.now(),
                    "toolu_n", "noop", null, false)
            ),
            "glm-5.1",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        String body = server.takeRequest().getBody().readUtf8();
        JsonNode block = new ObjectMapper().readTree(body)
            .get("messages").get(0).get("content").get(0);

        assertThat(block.get("content").asText()).isEmpty();
        assertThat(block.has("is_error")).isFalse();
    }

    @Test
    void adjacentToolResultsBecomeSingleUserMessageWithMultipleBlocks() throws Exception {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(
                new ToolResultMessage(UUID.randomUUID(), Instant.now(),
                    "toolu_a", "t1", "result-a", false),
                new ToolResultMessage(UUID.randomUUID(), Instant.now(),
                    "toolu_b", "t2", "result-b", false)
            ),
            "glm-5.1",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        String body = server.takeRequest().getBody().readUtf8();
        JsonNode messages = new ObjectMapper().readTree(body).get("messages");

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("user");

        assertThat(messages.get(0).get("content").get(0).get("tool_use_id").asText())
            .isEqualTo("toolu_a");
        assertThat(messages.get(0).get("content").get(1).get("tool_use_id").asText())
            .isEqualTo("toolu_b");
    }

    @Test
    void toolDefinitionsHaveCorrectShape() throws Exception {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "go")),
            "glm-5.1",
            null,
            List.of(
                new ModelToolDefinition("search", "Search files", Map.of("type", "object")),
                new ModelToolDefinition("read", "Read a file", Map.of("type", "object"))
            )
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        String body = server.takeRequest().getBody().readUtf8();
        JsonNode tools = new ObjectMapper().readTree(body).get("tools");

        assertThat(tools).hasSize(2);
        assertThat(tools.get(0).get("name").asText()).isEqualTo("search");
        assertThat(tools.get(0).get("description").asText()).isEqualTo("Search files");
        assertThat(tools.get(0).get("input_schema").get("type").asText()).isEqualTo("object");
    }

    @Test
    void serializedToolDefinitionsPreserveCriticalGuidance() throws Exception {
        enqueueHappyPath();

        List<ModelToolDefinition> toolDefs = List.of(
            new FileReadTool(new com.clawcode.agent.tools.file.FileReadStateStore()),
            new FileListTool(),
            new FileGlobTool(),
            new FileSearchTool(),
            new FileWriteTool(new com.clawcode.agent.tools.file.FileReadStateStore()),
            new FileEditTool(new FileReadStateStore()),
            new PowerShellTool(new PowerShellToolProperties(30))
        ).stream()
            .map(Tool::definition)
            .map(d -> new ModelToolDefinition(d.name(), d.description(), d.inputSchema()))
            .toList();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "go")),
            "glm-5.1",
            null,
            toolDefs
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        String body = server.takeRequest().getBody().readUtf8();
        JsonNode tools = new ObjectMapper().readTree(body).get("tools");

        // file_read
        JsonNode fileRead = assertFindTool(tools, "file_read");
        assertThat(fileRead.get("description").asText())
            .contains("read-only")
            .containsIgnoringCase("targeted")
            .contains("not directories")
            .contains("file_list")
            .contains("file_glob")
            .contains("file_search");
        assertThat(fileRead.get("input_schema").get("properties").get("path").get("description").asText())
            .contains("read-only")
            .containsIgnoringCase("targeted");

        // file_list
        assertThat(assertFindTool(tools, "file_list").get("description").asText())
            .contains("direct children")
            .contains("not recursive")
            .contains("file_read");

        // file_glob
        assertThat(assertFindTool(tools, "file_glob").get("description").asText())
            .contains("glob")
            .contains("bounded")
            .contains("before reading multiple files")
            .contains("file_read");

        // file_search
        assertThat(assertFindTool(tools, "file_search").get("description").asText())
            .contains("content search")
            .contains("instead of shell")
            .contains("bounded")
            .contains("limit");

        // file_write
        assertThat(assertFindTool(tools, "file_write").get("description").asText())
            .contains("full overwrite")
            .contains("read first")
            .contains("existing file")
            .containsIgnoringCase("do not create docs unless explicitly requested");

        // file_edit
        JsonNode fileEdit = assertFindTool(tools, "file_edit");
        assertThat(fileEdit.get("description").asText())
            .contains("targeted edit")
            .contains("existing file")
            .contains("file_read")
            .contains("exactly one occurrence")
            .containsIgnoringCase("not for creating new files");
        assertThat(fileEdit.get("input_schema").get("properties").get("old_text").get("description").asText())
            .containsIgnoringCase("exact text")
            .contains("exactly once");
        assertThat(fileEdit.get("input_schema").get("properties").get("new_text").get("description").asText())
            .containsIgnoringCase("replacement text");

        // powershell
        assertThat(assertFindTool(tools, "powershell").get("description").asText())
            .contains("build")
            .contains("git")
            .contains("system commands")
            .containsIgnoringCase("do not use for file read/search/write");
    }

    @Test
    void fullConversationHistoryPreservesMessageOrder() throws Exception {
        enqueueHappyPath();

        var request = new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(
                new UserMessage(UUID.randomUUID(), Instant.now(), "u1"),
                new AssistantMessage(UUID.randomUUID(), Instant.now(), "a1"),
                new UserMessage(UUID.randomUUID(), Instant.now(), "u2"),
                new AssistantMessage(UUID.randomUUID(), Instant.now(), List.of(
                    new AssistantToolUseBlock("toolu_1", "t", Map.of())
                )),
                new ToolResultMessage(UUID.randomUUID(), Instant.now(),
                    "toolu_1", "t", "ok", false),
                new UserMessage(UUID.randomUUID(), Instant.now(), "u3")
            ),
            "glm-5.1",
            null
        );

        client.stream(request).blockLast(Duration.ofSeconds(5));

        String body = server.takeRequest().getBody().readUtf8();
        JsonNode messages = new ObjectMapper().readTree(body).get("messages");

        assertThat(messages).hasSize(6);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(0).get("content").asText()).isEqualTo("u1");

        assertThat(messages.get(1).get("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(1).get("content").asText()).isEqualTo("a1");

        assertThat(messages.get(2).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(2).get("content").asText()).isEqualTo("u2");

        assertThat(messages.get(3).get("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(3).get("content").isArray()).isTrue();

        assertThat(messages.get(4).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(4).get("content").get(0).get("type").asText())
            .isEqualTo("tool_result");

        assertThat(messages.get(5).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(5).get("content").asText()).isEqualTo("u3");
    }

    // ── Wire-format: fragmented SSE tool input ──────────────────────────

    @Test
    void manyFragmentToolInputAccumulatesIntoSingleObject() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"glm-5.1"}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_multi","name":"write_file"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"pa"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"th\\":"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\\"/tmp/"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"out.txt\\""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":",\\"con"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"tent\\":"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\\"hello\\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_stop
            data: {"type":"message_stop"}

            """;

        server.enqueue(sseResponse(sse));

        StepVerifier.create(client.stream(defaultRequest()))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent)
            .expectNextMatches(e -> e instanceof ModelToolUseEvent tu
                && tu.toolCallId().equals("toolu_multi")
                && tu.toolName().equals("write_file")
                && tu.input() instanceof Map
                && ((Map<?, ?>) tu.input()).get("path").equals("/tmp/out.txt")
                && ((Map<?, ?>) tu.input()).get("content").equals("hello"))
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    @Test
    void twoConcurrentToolUseStreamsParsedIndependently() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"glm-5.1"}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_a","name":"tool_a"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"x\\":"}}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_b","name":"tool_b"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"1}"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"y\\":2}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: message_stop
            data: {"type":"message_stop"}

            """;

        server.enqueue(sseResponse(sse));

        StepVerifier.create(client.stream(defaultRequest()))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent)
            .expectNextMatches(e -> e instanceof ModelToolUseEvent tu
                && tu.toolCallId().equals("toolu_a")
                && tu.toolName().equals("tool_a")
                && ((Map<?, ?>) tu.input()).get("x").equals(1))
            .expectNextMatches(e -> e instanceof ModelToolUseEvent tu
                && tu.toolCallId().equals("toolu_b")
                && tu.toolName().equals("tool_b")
                && ((Map<?, ?>) tu.input()).get("y").equals(2))
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    @Test
    void toolInputNumericTypesPreserved() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"glm-5.1"}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_num","name":"calc"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"count\\":5,\\"price\\":19.99,\\"active\\":true}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_stop
            data: {"type":"message_stop"}

            """;

        server.enqueue(sseResponse(sse));

        StepVerifier.create(client.stream(defaultRequest()))
            .expectNextMatches(e -> e instanceof ModelStreamStartedEvent)
            .expectNextMatches(e -> e instanceof ModelToolUseEvent tu
                && tu.toolCallId().equals("toolu_num")
                && tu.input() instanceof Map
                && ((Map<?, ?>) tu.input()).get("count").equals(5)
                && ((Map<?, ?>) tu.input()).get("price").equals(19.99)
                && ((Map<?, ?>) tu.input()).get("active").equals(true))
            .expectNextMatches(e -> e instanceof ModelCompletedEvent)
            .verifyComplete();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void enqueueHappyPath() {
        String sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"glm-5.1"}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"text":"Hello"}}

            event: message_stop
            data: {"type":"message_stop"}

            """;
        server.enqueue(sseResponse(sse));
    }

    private ModelRequest defaultRequest() {
        return new ModelRequest(
            UUID.randomUUID().toString(),
            List.of(new UserMessage(UUID.randomUUID(), Instant.now(), "hello")),
            "glm-5.1",
            null
        );
    }

    private MockResponse sseResponse(String body) {
        return new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body);
    }

    private static JsonNode assertFindTool(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (tool.get("name").asText().equals(name)) {
                return tool;
            }
        }
        throw new AssertionError("Tool not found in serialized definitions: " + name);
    }

    private static long countAssistantTextMessages(JsonNode messages, String text) {
        long count = 0;
        for (JsonNode message : messages) {
            if ("assistant".equals(message.path("role").asText())
                && message.path("content").isTextual()
                && text.equals(message.path("content").asText())) {
                count++;
            }
        }
        return count;
    }
}
