package com.clawcode.agent.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawcode.agent.cli.model.CliQueryEvent;
import com.clawcode.agent.cli.model.MessageAck;
import com.clawcode.agent.cli.model.ReplayPage;
import com.clawcode.agent.cli.model.SessionInfo;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

public class HttpAgentApiClient implements AgentApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final WebClient client;
    private final Duration streamTimeout;

    public HttpAgentApiClient(CliProperties props) {
        HttpClient httpClient = HttpClient.create()
            .responseTimeout(Duration.ofMillis(props.timeoutMs()));

        WebClient.Builder builder = WebClient.builder()
            .baseUrl(props.baseUrl())
            .defaultHeaders(headers -> applyApiKey(headers, props))
            .clientConnector(new ReactorClientHttpConnector(httpClient));

        this.client = builder.build();
        this.streamTimeout = Duration.ofMillis(props.streamReadTimeoutMs());
    }

    HttpAgentApiClient(WebClient client, long streamReadTimeoutMs) {
        this.client = client;
        this.streamTimeout = Duration.ofMillis(streamReadTimeoutMs);
    }

    @Override
    public Mono<SessionInfo> createSession() {
        return client.post()
            .uri("/api/sessions")
            .retrieve()
            .bodyToMono(SessionInfo.class)
            .onErrorMap(WebClientResponseException.class, this::mapError);
    }

    @Override
    public Mono<MessageAck> sendMessage(String sessionId, String content, List<String> skillIds) {
        var body = new AgentApiDtos.SubmitMessageRequest(content,
            skillIds != null && !skillIds.isEmpty() ? skillIds : null);

        return client.post()
            .uri("/api/sessions/{sessionId}/messages", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(MessageAck.class)
            .onErrorMap(WebClientResponseException.class, this::mapError);
    }

    @Override
    public Flux<CliQueryEvent> attachStream(String sessionId) {
        return client.get()
            .uri("/api/sessions/{sessionId}/stream", sessionId)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .retrieve()
            .bodyToFlux(String.class)
            .filter(line -> !line.isBlank())
            .map(this::parseEvent)
            .timeout(streamTimeout)
            .onErrorMap(WebClientResponseException.class, this::mapError);
    }

    @Override
    public Mono<ReplayPage> replay(String sessionId, int after, int limit) {
        return client.get()
            .uri(uriBuilder -> uriBuilder
                .path("/api/sessions/{sessionId}/replay")
                .queryParam("after", after)
                .queryParam("limit", limit)
                .build(sessionId))
            .retrieve()
            .bodyToMono(ReplayPage.class)
            .onErrorMap(WebClientResponseException.class, this::mapError);
    }

    @Override
    public Mono<AgentApiDtos.TaskSubmitResult> submitTask(AgentApiDtos.SubmitTaskRequest request) {
        return client.post()
            .uri("/api/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(AgentApiDtos.TaskSubmitResult.class)
            .onErrorMap(WebClientResponseException.class, this::mapError);
    }

    @Override
    public Mono<AgentApiDtos.TaskStatus> taskStatus(String taskId) {
        return client.get()
            .uri("/api/tasks/{taskId}", taskId)
            .retrieve()
            .bodyToMono(AgentApiDtos.TaskStatus.class)
            .onErrorMap(WebClientResponseException.class, this::mapError);
    }

    @Override
    public Mono<AgentApiDtos.TaskResult> taskResult(String taskId) {
        return client.get()
            .uri("/api/tasks/{taskId}/result", taskId)
            .retrieve()
            .bodyToMono(AgentApiDtos.TaskResult.class)
            .onErrorMap(WebClientResponseException.class, this::mapError);
    }

    private CliQueryEvent parseEvent(String json) {
        try {
            return MAPPER.readValue(json, CliQueryEvent.class);
        } catch (JsonProcessingException e) {
            throw new CliApiException("Failed to parse stream event: " + e.getMessage(),
                0, CliApiException.ErrorType.TRANSPORT);
        }
    }

    private Throwable mapError(WebClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        int status = ex.getStatusCode().value();
        return switch (status) {
            case 401 -> new CliAuthException("Unauthorized: invalid or missing API key");
            case 403 -> new CliAuthException("Forbidden: insufficient permissions", 403);
            case 404 -> new CliNotFoundException(body);
            case 409 -> new CliConflictException(body);
            case 422 -> new CliValidationException(body);
            case 429 -> new CliRateLimitException("Rate limited: too many requests");
            default -> new CliApiException("HTTP " + status + ": " + body,
                status, status >= 500 ? CliApiException.ErrorType.SERVER_ERROR : null);
        };
    }

    private static void applyApiKey(HttpHeaders headers, CliProperties props) {
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            headers.set(props.apiKeyHeader(), props.apiKey());
        }
    }
}
