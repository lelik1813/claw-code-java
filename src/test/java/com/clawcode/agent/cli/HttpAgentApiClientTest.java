package com.clawcode.agent.cli;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpAgentApiClientTest {

    private MockWebServer server;
    private HttpAgentApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("").toString();
        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();
        client = new HttpAgentApiClient(webClient, 5000);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    // ═══════════════════════════════════════════════════════════
    //  createSession
    // ═══════════════════════════════════════════════════════════

    @Nested
    class CreateSession {

        @Test
        void returnsSessionInfo() throws InterruptedException {
            server.enqueue(json("{\"sessionId\":\"s-1\",\"createdAt\":\"2026-04-22T12:00:00Z\"}"));

            StepVerifier.create(client.createSession())
                .assertNext(info -> {
                    assertThat(info.sessionId()).isEqualTo("s-1");
                    assertThat(info.createdAt()).isEqualTo("2026-04-22T12:00:00Z");
                })
                .verifyComplete();

            RecordedRequest req = server.takeRequest();
            assertThat(req.getMethod()).isEqualTo("POST");
            assertThat(req.getPath()).isEqualTo("/api/sessions");
        }

        @Test
        void includesApiKeyHeader() throws InterruptedException {
            CliProperties props = new CliProperties(
                server.url("").toString(), "X-Token", "secret-key", 5000, 5000);
            HttpAgentApiClient keyed = new HttpAgentApiClient(props);

            server.enqueue(json("{\"sessionId\":\"s-2\",\"createdAt\":\"2026-01-01T00:00:00Z\"}"));

            StepVerifier.create(keyed.createSession())
                .expectNextCount(1)
                .verifyComplete();

            assertThat(server.takeRequest().getHeader("X-Token")).isEqualTo("secret-key");
        }

        @Test
        void noApiKeyHeader_whenBlank() throws InterruptedException {
            CliProperties props = new CliProperties(
                server.url("").toString(), "X-Token", "", 5000, 5000);
            HttpAgentApiClient keyed = new HttpAgentApiClient(props);

            server.enqueue(json("{\"sessionId\":\"s-3\",\"createdAt\":\"2026-01-01T00:00:00Z\"}"));

            StepVerifier.create(keyed.createSession()).expectNextCount(1).verifyComplete();

            assertThat(server.takeRequest().getHeader("X-Token")).isNull();
        }

        @Test
        void ignoresUnknownFields() {
            server.enqueue(json("{\"sessionId\":\"s-4\",\"createdAt\":\"t\",\"extra\":true}"));

            StepVerifier.create(client.createSession())
                .assertNext(info -> assertThat(info.sessionId()).isEqualTo("s-4"))
                .verifyComplete();
        }

        @Test
        void error_401() {
            server.enqueue(error(401, "Unauthorized"));
            StepVerifier.create(client.createSession())
                .expectErrorMatches(e -> e instanceof CliAuthException && e.getMessage().contains("Unauthorized"))
                .verify();
        }

        @Test
        void error_403() {
            server.enqueue(error(403, "Forbidden"));
            StepVerifier.create(client.createSession())
                .expectErrorMatches(e -> e instanceof CliAuthException && e.getMessage().contains("Forbidden"))
                .verify();
        }

        @Test
        void error_404() {
            server.enqueue(error(404, "Not found"));
            StepVerifier.create(client.createSession())
                .expectErrorMatches(e -> e instanceof CliNotFoundException)
                .verify();
        }

        @Test
        void error_409() {
            server.enqueue(error(409, "Session already exists"));
            StepVerifier.create(client.createSession())
                .expectErrorMatches(e -> e instanceof CliConflictException && e.getMessage().contains("Session already exists"))
                .verify();
        }

        @Test
        void error_422() {
            server.enqueue(error(422, "Invalid params"));
            StepVerifier.create(client.createSession())
                .expectErrorMatches(e -> e instanceof CliValidationException && e.getMessage().contains("Invalid params"))
                .verify();
        }

        @Test
        void error_429() {
            server.enqueue(error(429, "Too Many Requests"));
            StepVerifier.create(client.createSession())
                .expectErrorMatches(e -> e instanceof CliRateLimitException && e.getMessage().contains("Rate limited"))
                .verify();
        }

        @Test
        void error_500() {
            server.enqueue(error(500, "Internal Server Error"));
            StepVerifier.create(client.createSession())
                .expectErrorMatches(e -> e instanceof CliApiException
                    && ((CliApiException) e).statusCode() == 500
                    && ((CliApiException) e).errorType() == CliApiException.ErrorType.SERVER_ERROR)
                .verify();
        }

        @Test
        void error_502() {
            server.enqueue(error(502, "Bad Gateway"));
            StepVerifier.create(client.createSession())
                .expectErrorMatches(e -> e instanceof CliApiException
                    && ((CliApiException) e).errorType() == CliApiException.ErrorType.SERVER_ERROR)
                .verify();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  sendMessage
    // ═══════════════════════════════════════════════════════════

    @Nested
    class SendMessage {

        @Test
        void returnsAck() throws InterruptedException {
            server.enqueue(json("{\"sessionId\":\"s-1\",\"accepted\":true}"));

            StepVerifier.create(client.sendMessage("s-1", "hello", null))
                .assertNext(ack -> {
                    assertThat(ack.sessionId()).isEqualTo("s-1");
                    assertThat(ack.accepted()).isTrue();
                })
                .verifyComplete();

            RecordedRequest req = server.takeRequest();
            assertThat(req.getMethod()).isEqualTo("POST");
            assertThat(req.getPath()).isEqualTo("/api/sessions/s-1/messages");
            assertThat(req.getBody().readUtf8()).contains("\"content\":\"hello\"");
        }

        @Test
        void rejected() {
            server.enqueue(json("{\"sessionId\":\"s-1\",\"accepted\":false}"));

            StepVerifier.create(client.sendMessage("s-1", "blocked", null))
                .assertNext(ack -> assertThat(ack.accepted()).isFalse())
                .verifyComplete();
        }

        @Test
        void nullSkillIds_omitsField() throws InterruptedException {
            server.enqueue(json("{\"sessionId\":\"s-1\",\"accepted\":true}"));

            StepVerifier.create(client.sendMessage("s-1", "hi", null))
                .expectNextCount(1)
                .verifyComplete();

            String body = server.takeRequest().getBody().readUtf8();
            assertThat(body).doesNotContain("skillIds");
        }

        @Test
        void emptySkillIds_omitsField() throws InterruptedException {
            server.enqueue(json("{\"sessionId\":\"s-1\",\"accepted\":true}"));

            StepVerifier.create(client.sendMessage("s-1", "hi", List.of()))
                .expectNextCount(1)
                .verifyComplete();

            String body = server.takeRequest().getBody().readUtf8();
            assertThat(body).doesNotContain("skillIds");
        }

        @Test
        void withSkillIds_includesInBody() throws InterruptedException {
            server.enqueue(json("{\"sessionId\":\"s-3\",\"accepted\":true}"));

            StepVerifier.create(client.sendMessage("s-3", "go", List.of("translator", "summarizer")))
                .expectNextCount(1)
                .verifyComplete();

            String body = server.takeRequest().getBody().readUtf8();
            assertThat(body).contains("\"skillIds\"");
            assertThat(body).contains("translator");
            assertThat(body).contains("summarizer");
        }

        @Test
        void contentType_isJson() throws InterruptedException {
            server.enqueue(json("{\"sessionId\":\"s-1\",\"accepted\":true}"));

            StepVerifier.create(client.sendMessage("s-1", "hi", null)).expectNextCount(1).verifyComplete();

            assertThat(server.takeRequest().getHeader("Content-Type")).contains("application/json");
        }

        @Test
        void error_401() {
            server.enqueue(error(401, "Unauthorized"));
            StepVerifier.create(client.sendMessage("s-1", "hi", null))
                .expectErrorMatches(e -> e instanceof CliAuthException).verify();
        }

        @Test
        void error_403() {
            server.enqueue(error(403, "Forbidden"));
            StepVerifier.create(client.sendMessage("s-1", "hi", null))
                .expectErrorMatches(e -> e instanceof CliAuthException && e.getMessage().contains("Forbidden"))
                .verify();
        }

        @Test
        void error_404() {
            server.enqueue(error(404, "Session not found"));
            StepVerifier.create(client.sendMessage("missing", "hi", null))
                .expectErrorMatches(e -> e instanceof CliNotFoundException).verify();
        }

        @Test
        void error_409() {
            server.enqueue(error(409, "Session locked"));
            StepVerifier.create(client.sendMessage("s-1", "hi", null))
                .expectErrorMatches(e -> e instanceof CliConflictException).verify();
        }

        @Test
        void error_422() {
            server.enqueue(error(422, "Content is required"));
            StepVerifier.create(client.sendMessage("s-1", "hi", null))
                .expectErrorMatches(e -> e instanceof CliValidationException).verify();
        }

        @Test
        void error_429() {
            server.enqueue(error(429, "Too Many Requests"));
            StepVerifier.create(client.sendMessage("s-1", "hi", null))
                .expectErrorMatches(e -> e instanceof CliRateLimitException).verify();
        }

        @Test
        void error_500() {
            server.enqueue(error(500, "Internal Server Error"));
            StepVerifier.create(client.sendMessage("s-1", "hi", null))
                .expectErrorMatches(e -> e instanceof CliApiException && e.getMessage().contains("HTTP 500"))
                .verify();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  attachStream
    // ═══════════════════════════════════════════════════════════

    @Nested
    class AttachStream {

        @Test
        void receivesDeltaEvents() throws InterruptedException {
            server.enqueue(sse("data: {\"type\":\"delta\",\"text\":\"e1\"}\n\ndata: {\"type\":\"delta\",\"text\":\"e2\"}\n\n"));

            StepVerifier.create(client.attachStream("s-1").take(2))
                .assertNext(e -> {
                    assertThat(e).isInstanceOf(com.clawcode.agent.cli.model.CliQueryEvent.Delta.class);
                    assertThat(((com.clawcode.agent.cli.model.CliQueryEvent.Delta) e).text()).isEqualTo("e1");
                })
                .assertNext(e -> {
                    assertThat(e).isInstanceOf(com.clawcode.agent.cli.model.CliQueryEvent.Delta.class);
                    assertThat(((com.clawcode.agent.cli.model.CliQueryEvent.Delta) e).text()).isEqualTo("e2");
                })
                .verifyComplete();

            RecordedRequest req = server.takeRequest();
            assertThat(req.getMethod()).isEqualTo("GET");
            assertThat(req.getPath()).isEqualTo("/api/sessions/s-1/stream");
            assertThat(req.getHeader("Accept")).contains("text/event-stream");
        }

        @Test
        void receivesAllEventTypes() {
            server.enqueue(sse(
                "data: {\"type\":\"started\"}\n\n"
                + "data: {\"type\":\"delta\",\"text\":\"thinking\"}\n\n"
                + "data: {\"type\":\"tool_call\",\"toolCallId\":\"c1\",\"toolName\":\"read\"}\n\n"
                + "data: {\"type\":\"tool_result\",\"toolCallId\":\"c1\",\"toolName\":\"read\",\"isError\":false,\"summary\":\"42 lines\"}\n\n"
                + "data: {\"type\":\"completed\"}\n\n"));

            StepVerifier.create(client.attachStream("s-1").take(5))
                .assertNext(e -> assertThat(e).isInstanceOf(com.clawcode.agent.cli.model.CliQueryEvent.Started.class))
                .assertNext(e -> assertThat(e).isInstanceOf(com.clawcode.agent.cli.model.CliQueryEvent.Delta.class))
                .assertNext(e -> assertThat(e).isInstanceOf(com.clawcode.agent.cli.model.CliQueryEvent.ToolCall.class))
                .assertNext(e -> assertThat(e).isInstanceOf(com.clawcode.agent.cli.model.CliQueryEvent.ToolResult.class))
                .assertNext(e -> assertThat(e).isInstanceOf(com.clawcode.agent.cli.model.CliQueryEvent.Completed.class))
                .verifyComplete();
        }

        @Test
        void receivesErrorEvent() {
            server.enqueue(sse("data: {\"type\":\"error\",\"message\":\"rate limited\",\"code\":\"429\"}\n\n"));

            StepVerifier.create(client.attachStream("s-1").take(1))
                .assertNext(e -> {
                    assertThat(e).isInstanceOf(com.clawcode.agent.cli.model.CliQueryEvent.Error.class);
                    assertThat(((com.clawcode.agent.cli.model.CliQueryEvent.Error) e).message()).isEqualTo("rate limited");
                    assertThat(((com.clawcode.agent.cli.model.CliQueryEvent.Error) e).code()).isEqualTo("429");
                })
                .verifyComplete();
        }

        @Test
        void unknownType_fallsBackToUnknown() {
            server.enqueue(sse("data: {\"type\":\"future_event\",\"data\":\"x\"}\n\n"));

            StepVerifier.create(client.attachStream("s-1").take(1))
                .assertNext(e -> {
                    assertThat(e).isInstanceOf(com.clawcode.agent.cli.model.CliQueryEvent.Unknown.class);
                    assertThat(((com.clawcode.agent.cli.model.CliQueryEvent.Unknown) e).type()).isEqualTo("future_event");
                })
                .verifyComplete();
        }

        @Test
        void toolResult_withError() {
            server.enqueue(sse("data: {\"type\":\"tool_result\",\"toolCallId\":\"c2\",\"toolName\":\"bash\",\"isError\":true,\"summary\":\"exit 1\"}\n\n"));

            StepVerifier.create(client.attachStream("s-1").take(1))
                .assertNext(e -> {
                    var tr = (com.clawcode.agent.cli.model.CliQueryEvent.ToolResult) e;
                    assertThat(tr.toolName()).isEqualTo("bash");
                    assertThat(tr.isError()).isTrue();
                    assertThat(tr.summary()).isEqualTo("exit 1");
                })
                .verifyComplete();
        }

        @Test
        void malformedJson_throwsTransportError() {
            server.enqueue(sse("data: {bad json\n\n"));

            StepVerifier.create(client.attachStream("s-1"))
                .expectErrorMatches(e -> e instanceof CliApiException
                    && ((CliApiException) e).errorType() == CliApiException.ErrorType.TRANSPORT)
                .verify();
        }

        @Test
        void emptyStream_completes() {
            server.enqueue(sse(""));
            StepVerifier.create(client.attachStream("s-1")).verifyComplete();
        }

        @Test
        void blankLinesAreFiltered() {
            server.enqueue(sse("data: {\"type\":\"delta\",\"text\":\"ok\"}\n\n\n\n"));

            StepVerifier.create(client.attachStream("s-1").take(1))
                .assertNext(e -> assertThat(e).isInstanceOf(com.clawcode.agent.cli.model.CliQueryEvent.Delta.class))
                .verifyComplete();
        }

        @Test
        void error_401() {
            server.enqueue(error(401, "Unauthorized"));
            StepVerifier.create(client.attachStream("s-1"))
                .expectErrorMatches(e -> e instanceof CliAuthException).verify();
        }

        @Test
        void error_403() {
            server.enqueue(error(403, "Forbidden"));
            StepVerifier.create(client.attachStream("s-1"))
                .expectErrorMatches(e -> e instanceof CliAuthException && e.getMessage().contains("Forbidden"))
                .verify();
        }

        @Test
        void error_404() {
            server.enqueue(error(404, "Not found"));
            StepVerifier.create(client.attachStream("missing"))
                .expectErrorMatches(e -> e instanceof CliNotFoundException).verify();
        }

        @Test
        void error_409() {
            server.enqueue(error(409, "Session locked"));
            StepVerifier.create(client.attachStream("s-1"))
                .expectErrorMatches(e -> e instanceof CliConflictException).verify();
        }

        @Test
        void error_422() {
            server.enqueue(error(422, "Invalid session"));
            StepVerifier.create(client.attachStream("s-1"))
                .expectErrorMatches(e -> e instanceof CliValidationException).verify();
        }

        @Test
        void error_429() {
            server.enqueue(error(429, "Too Many Requests"));
            StepVerifier.create(client.attachStream("s-1"))
                .expectErrorMatches(e -> e instanceof CliRateLimitException).verify();
        }

        @Test
        void error_500() {
            server.enqueue(error(500, "Internal Server Error"));
            StepVerifier.create(client.attachStream("s-1"))
                .expectErrorMatches(e -> e instanceof CliApiException && e.getMessage().contains("HTTP 500"))
                .verify();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  replay
    // ═══════════════════════════════════════════════════════════

    @Nested
    class Replay {

        @Test
        void returnsMessages() throws InterruptedException {
            server.enqueue(json("{\"messages\":[" +
                "{\"role\":\"user\",\"content\":\"hello\"}," +
                "{\"role\":\"assistant\",\"content\":\"hi there\"}" +
                "],\"nextCursor\":2,\"hasMore\":false}"));

            StepVerifier.create(client.replay("s-1", 0, 100))
                .assertNext(page -> {
                    assertThat(page.messages()).hasSize(2);
                    assertThat(page.messages().get(0).role()).isEqualTo("user");
                    assertThat(page.messages().get(1).content()).isEqualTo("hi there");
                    assertThat(page.nextCursor()).isEqualTo(2);
                    assertThat(page.hasMore()).isFalse();
                })
                .verifyComplete();

            RecordedRequest req = server.takeRequest();
            assertThat(req.getMethod()).isEqualTo("GET");
            assertThat(req.getPath()).isEqualTo("/api/sessions/s-1/replay?after=0&limit=100");
        }

        @Test
        void emptyPage() {
            server.enqueue(json("{\"messages\":[],\"nextCursor\":0,\"hasMore\":false}"));

            StepVerifier.create(client.replay("s-1", 5, 50))
                .assertNext(page -> {
                    assertThat(page.messages()).isEmpty();
                    assertThat(page.nextCursor()).isEqualTo(0);
                    assertThat(page.hasMore()).isFalse();
                })
                .verifyComplete();
        }

        @Test
        void paginated_hasMore() {
            server.enqueue(json("{\"messages\":[{\"role\":\"user\",\"content\":\"msg\"}]," +
                "\"nextCursor\":101,\"hasMore\":true}"));

            StepVerifier.create(client.replay("s-1", 0, 100))
                .assertNext(page -> {
                    assertThat(page.messages()).hasSize(1);
                    assertThat(page.hasMore()).isTrue();
                    assertThat(page.nextCursor()).isEqualTo(101);
                })
                .verifyComplete();
        }

        @Test
        void queryParams_withNonZeroAfter() throws InterruptedException {
            server.enqueue(json("{\"messages\":[],\"nextCursor\":50,\"hasMore\":false}"));

            StepVerifier.create(client.replay("s-1", 42, 10)).expectNextCount(1).verifyComplete();

            assertThat(server.takeRequest().getPath()).isEqualTo("/api/sessions/s-1/replay?after=42&limit=10");
        }

        @Test
        void malformedBody_throwsError() {
            server.enqueue(json("not valid json{{{"));

            StepVerifier.create(client.replay("s-1", 0, 100))
                .expectError().verify();
        }

        @Test
        void error_401() {
            server.enqueue(error(401, "Unauthorized"));
            StepVerifier.create(client.replay("s-1", 0, 100))
                .expectErrorMatches(e -> e instanceof CliAuthException).verify();
        }

        @Test
        void error_403() {
            server.enqueue(error(403, "Forbidden"));
            StepVerifier.create(client.replay("s-1", 0, 100))
                .expectErrorMatches(e -> e instanceof CliAuthException).verify();
        }

        @Test
        void error_404() {
            server.enqueue(error(404, "Not found"));
            StepVerifier.create(client.replay("missing", 0, 100))
                .expectErrorMatches(e -> e instanceof CliNotFoundException).verify();
        }

        @Test
        void error_409() {
            server.enqueue(error(409, "Session locked"));
            StepVerifier.create(client.replay("s-1", 0, 100))
                .expectErrorMatches(e -> e instanceof CliConflictException).verify();
        }

        @Test
        void error_422() {
            server.enqueue(error(422, "Invalid cursor"));
            StepVerifier.create(client.replay("s-1", -1, 100))
                .expectErrorMatches(e -> e instanceof CliValidationException).verify();
        }

        @Test
        void error_429() {
            server.enqueue(error(429, "Too Many Requests"));
            StepVerifier.create(client.replay("s-1", 0, 100))
                .expectErrorMatches(e -> e instanceof CliRateLimitException).verify();
        }

        @Test
        void error_500() {
            server.enqueue(error(500, "Internal Server Error"));
            StepVerifier.create(client.replay("s-1", 0, 100))
                .expectErrorMatches(e -> e instanceof CliApiException && e.getMessage().contains("HTTP 500"))
                .verify();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  submitTask
    // ═══════════════════════════════════════════════════════════

    @Nested
    class SubmitTask {

        @Test
        void returnsResult() throws InterruptedException {
            server.enqueue(new MockResponse().setResponseCode(202)
                .setBody("{\"taskId\":\"task-1\",\"status\":\"ACCEPTED\",\"acceptedAt\":\"2026-04-24T12:00:00Z\"}")
                .setHeader("Content-Type", "application/json"));

            var request = new AgentApiDtos.SubmitTaskRequest("s-1", null, null, "ls -la");
            StepVerifier.create(client.submitTask(request))
                .assertNext(result -> {
                    assertThat(result.taskId()).isEqualTo("task-1");
                    assertThat(result.status()).isEqualTo("ACCEPTED");
                    assertThat(result.acceptedAt()).isEqualTo("2026-04-24T12:00:00Z");
                })
                .verifyComplete();

            RecordedRequest req = server.takeRequest();
            assertThat(req.getMethod()).isEqualTo("POST");
            assertThat(req.getPath()).isEqualTo("/api/tasks");
            String body = req.getBody().readUtf8();
            assertThat(body).contains("\"sessionId\":\"s-1\"");
            assertThat(body).contains("\"input\":\"ls -la\"");
        }

        @Test
        void withAllFields() throws InterruptedException {
            server.enqueue(new MockResponse().setResponseCode(202)
                .setBody("{\"taskId\":\"task-2\",\"status\":\"ACCEPTED\",\"acceptedAt\":\"2026-01-01T00:00:00Z\"}")
                .setHeader("Content-Type", "application/json"));

            var request = new AgentApiDtos.SubmitTaskRequest("s-1", "turn-42", "shell", "cmd");
            StepVerifier.create(client.submitTask(request)).expectNextCount(1).verifyComplete();

            String body = server.takeRequest().getBody().readUtf8();
            assertThat(body).contains("\"turnId\":\"turn-42\"");
            assertThat(body).contains("\"taskType\":\"shell\"");
        }

        @Test
        void nullOptionalFields_includedAsNull() throws InterruptedException {
            server.enqueue(new MockResponse().setResponseCode(202)
                .setBody("{\"taskId\":\"task-3\",\"status\":\"ACCEPTED\",\"acceptedAt\":\"t\"}")
                .setHeader("Content-Type", "application/json"));

            var request = new AgentApiDtos.SubmitTaskRequest("s-1", null, null, "cmd");
            StepVerifier.create(client.submitTask(request)).expectNextCount(1).verifyComplete();

            String body = server.takeRequest().getBody().readUtf8();
            assertThat(body).contains("\"turnId\":null");
            assertThat(body).contains("\"taskType\":null");
        }

        @Test
        void includesApiKeyHeader() throws InterruptedException {
            CliProperties props = new CliProperties(
                server.url("").toString(), "X-Api-Key", "task-key", 5000, 5000);
            HttpAgentApiClient keyed = new HttpAgentApiClient(props);

            server.enqueue(new MockResponse().setResponseCode(202)
                .setBody("{\"taskId\":\"t\",\"status\":\"ACCEPTED\",\"acceptedAt\":\"t\"}")
                .setHeader("Content-Type", "application/json"));

            StepVerifier.create(keyed.submitTask(new AgentApiDtos.SubmitTaskRequest("s", null, null, "c")))
                .expectNextCount(1).verifyComplete();

            assertThat(server.takeRequest().getHeader("X-Api-Key")).isEqualTo("task-key");
        }

        @Test
        void contentType_isJson() throws InterruptedException {
            server.enqueue(new MockResponse().setResponseCode(202)
                .setBody("{\"taskId\":\"t\",\"status\":\"ACCEPTED\",\"acceptedAt\":\"t\"}")
                .setHeader("Content-Type", "application/json"));

            StepVerifier.create(client.submitTask(new AgentApiDtos.SubmitTaskRequest("s", null, null, "c")))
                .expectNextCount(1).verifyComplete();

            assertThat(server.takeRequest().getHeader("Content-Type")).contains("application/json");
        }

        @Test
        void error_401() {
            server.enqueue(error(401, "Unauthorized"));
            StepVerifier.create(client.submitTask(new AgentApiDtos.SubmitTaskRequest("s", null, null, "c")))
                .expectErrorMatches(e -> e instanceof CliAuthException).verify();
        }

        @Test
        void error_403() {
            server.enqueue(error(403, "Forbidden"));
            StepVerifier.create(client.submitTask(new AgentApiDtos.SubmitTaskRequest("s", null, null, "c")))
                .expectErrorMatches(e -> e instanceof CliAuthException).verify();
        }

        @Test
        void error_404() {
            server.enqueue(error(404, "Not found"));
            StepVerifier.create(client.submitTask(new AgentApiDtos.SubmitTaskRequest("s", null, null, "c")))
                .expectErrorMatches(e -> e instanceof CliNotFoundException).verify();
        }

        @Test
        void error_409() {
            server.enqueue(error(409, "Duplicate task"));
            StepVerifier.create(client.submitTask(new AgentApiDtos.SubmitTaskRequest("s", null, null, "c")))
                .expectErrorMatches(e -> e instanceof CliConflictException).verify();
        }

        @Test
        void error_422() {
            server.enqueue(error(422, "Invalid input"));
            StepVerifier.create(client.submitTask(new AgentApiDtos.SubmitTaskRequest("s", null, null, "c")))
                .expectErrorMatches(e -> e instanceof CliValidationException).verify();
        }

        @Test
        void error_429() {
            server.enqueue(error(429, "Too Many Requests"));
            StepVerifier.create(client.submitTask(new AgentApiDtos.SubmitTaskRequest("s", null, null, "c")))
                .expectErrorMatches(e -> e instanceof CliRateLimitException).verify();
        }

        @Test
        void error_500() {
            server.enqueue(error(500, "Internal Server Error"));
            StepVerifier.create(client.submitTask(new AgentApiDtos.SubmitTaskRequest("s", null, null, "c")))
                .expectErrorMatches(e -> e instanceof CliApiException && e.getMessage().contains("HTTP 500"))
                .verify();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  taskStatus
    // ═══════════════════════════════════════════════════════════

    @Nested
    class TaskStatus {

        @Test
        void returnsStatus() throws InterruptedException {
            server.enqueue(json("{\"taskId\":\"task-1\",\"status\":\"RUNNING\",\"updatedAt\":\"2026-04-24T12:01:00Z\",\"error\":null}"));

            StepVerifier.create(client.taskStatus("task-1"))
                .assertNext(s -> {
                    assertThat(s.taskId()).isEqualTo("task-1");
                    assertThat(s.status()).isEqualTo("RUNNING");
                    assertThat(s.updatedAt()).isEqualTo("2026-04-24T12:01:00Z");
                    assertThat(s.error()).isNull();
                })
                .verifyComplete();

            RecordedRequest req = server.takeRequest();
            assertThat(req.getMethod()).isEqualTo("GET");
            assertThat(req.getPath()).isEqualTo("/api/tasks/task-1");
        }

        @Test
        void withError() {
            server.enqueue(json("{\"taskId\":\"task-2\",\"status\":\"FAILED\",\"updatedAt\":\"t\",\"error\":\"oom\"}"));

            StepVerifier.create(client.taskStatus("task-2"))
                .assertNext(s -> {
                    assertThat(s.status()).isEqualTo("FAILED");
                    assertThat(s.error()).isEqualTo("oom");
                })
                .verifyComplete();
        }

        @Test
        void malformedBody_throwsError() {
            server.enqueue(json("not valid json{{{"));

            StepVerifier.create(client.taskStatus("task-1"))
                .expectError().verify();
        }

        @Test
        void error_401() {
            server.enqueue(error(401, "Unauthorized"));
            StepVerifier.create(client.taskStatus("task-1"))
                .expectErrorMatches(e -> e instanceof CliAuthException).verify();
        }

        @Test
        void error_403() {
            server.enqueue(error(403, "Forbidden"));
            StepVerifier.create(client.taskStatus("task-1"))
                .expectErrorMatches(e -> e instanceof CliAuthException).verify();
        }

        @Test
        void error_404() {
            server.enqueue(error(404, "Task not found"));
            StepVerifier.create(client.taskStatus("missing"))
                .expectErrorMatches(e -> e instanceof CliNotFoundException).verify();
        }

        @Test
        void error_409() {
            server.enqueue(error(409, "Conflict"));
            StepVerifier.create(client.taskStatus("task-1"))
                .expectErrorMatches(e -> e instanceof CliConflictException).verify();
        }

        @Test
        void error_422() {
            server.enqueue(error(422, "Invalid task ID"));
            StepVerifier.create(client.taskStatus("task-1"))
                .expectErrorMatches(e -> e instanceof CliValidationException).verify();
        }

        @Test
        void error_429() {
            server.enqueue(error(429, "Too Many Requests"));
            StepVerifier.create(client.taskStatus("task-1"))
                .expectErrorMatches(e -> e instanceof CliRateLimitException).verify();
        }

        @Test
        void error_500() {
            server.enqueue(error(500, "Internal Server Error"));
            StepVerifier.create(client.taskStatus("task-1"))
                .expectErrorMatches(e -> e instanceof CliApiException && e.getMessage().contains("HTTP 500"))
                .verify();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  taskResult
    // ═══════════════════════════════════════════════════════════

    @Nested
    class TaskResult {

        @Test
        void returnsCompletedResult() throws InterruptedException {
            server.enqueue(json("{\"taskId\":\"task-1\",\"status\":\"COMPLETED\",\"output\":\"file.txt\",\"error\":null}"));

            StepVerifier.create(client.taskResult("task-1"))
                .assertNext(r -> {
                    assertThat(r.taskId()).isEqualTo("task-1");
                    assertThat(r.status()).isEqualTo("COMPLETED");
                    assertThat(r.output()).isEqualTo("file.txt");
                    assertThat(r.error()).isNull();
                })
                .verifyComplete();

            RecordedRequest req = server.takeRequest();
            assertThat(req.getMethod()).isEqualTo("GET");
            assertThat(req.getPath()).isEqualTo("/api/tasks/task-1/result");
        }

        @Test
        void failedTask_withError() {
            server.enqueue(json("{\"taskId\":\"task-2\",\"status\":\"FAILED\",\"output\":null,\"error\":\"command timed out\"}"));

            StepVerifier.create(client.taskResult("task-2"))
                .assertNext(r -> {
                    assertThat(r.status()).isEqualTo("FAILED");
                    assertThat(r.error()).isEqualTo("command timed out");
                    assertThat(r.output()).isNull();
                })
                .verifyComplete();
        }

        @Test
        void malformedBody_throwsError() {
            server.enqueue(json("not valid json{{{"));

            StepVerifier.create(client.taskResult("task-1"))
                .expectError().verify();
        }

        @Test
        void error_401() {
            server.enqueue(error(401, "Unauthorized"));
            StepVerifier.create(client.taskResult("task-1"))
                .expectErrorMatches(e -> e instanceof CliAuthException).verify();
        }

        @Test
        void error_403() {
            server.enqueue(error(403, "Forbidden"));
            StepVerifier.create(client.taskResult("task-1"))
                .expectErrorMatches(e -> e instanceof CliAuthException).verify();
        }

        @Test
        void error_404() {
            server.enqueue(error(404, "Task not found"));
            StepVerifier.create(client.taskResult("missing"))
                .expectErrorMatches(e -> e instanceof CliNotFoundException).verify();
        }

        @Test
        void error_409() {
            server.enqueue(error(409, "Conflict"));
            StepVerifier.create(client.taskResult("task-1"))
                .expectErrorMatches(e -> e instanceof CliConflictException).verify();
        }

        @Test
        void error_422() {
            server.enqueue(error(422, "Invalid task"));
            StepVerifier.create(client.taskResult("task-1"))
                .expectErrorMatches(e -> e instanceof CliValidationException).verify();
        }

        @Test
        void error_429() {
            server.enqueue(error(429, "Too Many Requests"));
            StepVerifier.create(client.taskResult("task-1"))
                .expectErrorMatches(e -> e instanceof CliRateLimitException).verify();
        }

        @Test
        void error_500() {
            server.enqueue(error(500, "Internal Server Error"));
            StepVerifier.create(client.taskResult("task-1"))
                .expectErrorMatches(e -> e instanceof CliApiException && e.getMessage().contains("HTTP 500"))
                .verify();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  DTO validation
    // ═══════════════════════════════════════════════════════════

    @Nested
    class DtoValidation {

        @Test
        void submitMessageRequest_blankContent_throws() {
            assertThatThrownBy(() -> new AgentApiDtos.SubmitMessageRequest("", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content must not be blank");
        }

        @Test
        void submitMessageRequest_nullContent_throws() {
            assertThatThrownBy(() -> new AgentApiDtos.SubmitMessageRequest(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void submitTaskRequest_blankSessionId_throws() {
            assertThatThrownBy(() -> new AgentApiDtos.SubmitTaskRequest("", null, null, "cmd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId must not be blank");
        }

        @Test
        void submitTaskRequest_blankInput_throws() {
            assertThatThrownBy(() -> new AgentApiDtos.SubmitTaskRequest("s-1", null, null, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input must not be blank");
        }

        @Test
        void submitTaskRequest_nullSessionId_throws() {
            assertThatThrownBy(() -> new AgentApiDtos.SubmitTaskRequest(null, null, null, "cmd"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void submitTaskRequest_nullInput_throws() {
            assertThatThrownBy(() -> new AgentApiDtos.SubmitTaskRequest("s-1", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Mapper integration
    // ═══════════════════════════════════════════════════════════

    @Nested
    class MapperIntegration {

        @Test
        void authException_hasStatusCodeAndType() {
            var ex = new CliAuthException("bad key");
            assertThat(ex.statusCode()).isEqualTo(401);
            assertThat(ex.errorType()).isEqualTo(CliApiException.ErrorType.AUTH);

            var exit = CliExceptionMapper.map(ex);
            assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_AUTH);
        }

        @Test
        void conflictException_hasStatusCodeAndType() {
            var ex = new CliConflictException("dup");
            assertThat(ex.statusCode()).isEqualTo(409);
            assertThat(ex.errorType()).isEqualTo(CliApiException.ErrorType.CONFLICT);

            var exit = CliExceptionMapper.map(ex);
            assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_CONFLICT);
        }

        @Test
        void validationException_hasStatusCodeAndType() {
            var ex = new CliValidationException("bad input");
            assertThat(ex.statusCode()).isEqualTo(422);
            assertThat(ex.errorType()).isEqualTo(CliApiException.ErrorType.VALIDATION);

            var exit = CliExceptionMapper.map(ex);
            assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_VALIDATION);
        }

        @Test
        void serverErrorException_hasServerErrorType() {
            server.enqueue(error(500, "fail"));

            StepVerifier.create(client.createSession())
                .expectErrorMatches(e -> {
                    var api = (CliApiException) e;
                    return api.statusCode() == 500
                        && api.errorType() == CliApiException.ErrorType.SERVER_ERROR;
                })
                .verify();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  helpers
    // ═══════════════════════════════════════════════════════════

    private static MockResponse json(String body) {
        return new MockResponse()
            .setBody(body)
            .setHeader("Content-Type", "application/json");
    }

    private static MockResponse error(int status, String body) {
        return new MockResponse().setResponseCode(status).setBody(body);
    }

    private static MockResponse sse(String body) {
        return new MockResponse()
            .setBody(body)
            .setHeader("Content-Type", "text/event-stream");
    }
}
