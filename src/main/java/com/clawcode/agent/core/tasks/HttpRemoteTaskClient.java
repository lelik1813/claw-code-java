package com.clawcode.agent.core.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

public class HttpRemoteTaskClient implements RemoteTaskClient {

    private final String baseUrl;
    private final String authToken;
    private final long timeoutMs;
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpRemoteTaskClient(String baseUrl, String authToken, long timeoutMs) {
        this.baseUrl = baseUrl;
        this.authToken = authToken;
        this.timeoutMs = timeoutMs;
        this.webClient = WebClient.builder().build();
    }

    @Override
    public Mono<TaskRecord> submitRemote(TaskRecord task) {
        return webClient.post()
            .uri(baseUrl + "/tasks")
            .headers(h -> applyHeaders(h))
            .bodyValue(toSubmitBody(task))
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofMillis(timeoutMs))
            .map(body -> parseTaskRecord(body, task))
            .onErrorMap(WebClientResponseException.class, e ->
                new RemoteTaskException("HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e))
            .onErrorMap(WebClientRequestException.class, e ->
                new RemoteTaskException("I/O error: " + e.getMessage(), e))
            .onErrorMap(TimeoutException.class, e ->
                new RemoteTaskException("request timed out", e))
            .onErrorMap(IOException.class, e ->
                new RemoteTaskException("I/O error: " + e.getMessage(), e));
    }

    @Override
    public Mono<TaskRecord> fetchStatus(String taskId) {
        return webClient.get()
            .uri(baseUrl + "/tasks/" + taskId + "/status")
            .headers(h -> applyHeaders(h))
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofMillis(timeoutMs))
            .map(this::parseTaskRecordFromResponse)
            .onErrorMap(WebClientResponseException.class, e ->
                new RemoteTaskException("HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e))
            .onErrorMap(WebClientRequestException.class, e ->
                new RemoteTaskException("I/O error: " + e.getMessage(), e))
            .onErrorMap(TimeoutException.class, e ->
                new RemoteTaskException("request timed out", e));
    }

    @Override
    public Mono<TaskRecord> fetchResult(String taskId) {
        return webClient.get()
            .uri(baseUrl + "/tasks/" + taskId + "/result")
            .headers(h -> applyHeaders(h))
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofMillis(timeoutMs))
            .map(this::parseTaskRecordFromResponse)
            .onErrorMap(WebClientResponseException.class, e ->
                new RemoteTaskException("HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e))
            .onErrorMap(WebClientRequestException.class, e ->
                new RemoteTaskException("I/O error: " + e.getMessage(), e))
            .onErrorMap(TimeoutException.class, e ->
                new RemoteTaskException("request timed out", e));
    }

    @Override
    public Mono<Void> cancelRemote(String taskId) {
        return webClient.post()
            .uri(baseUrl + "/tasks/" + taskId + "/cancel")
            .headers(h -> applyHeaders(h))
            .retrieve()
            .toBodilessEntity()
            .timeout(Duration.ofMillis(timeoutMs))
            .then()
            .onErrorMap(WebClientResponseException.class, e ->
                new RemoteTaskException("HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e))
            .onErrorMap(WebClientRequestException.class, e ->
                new RemoteTaskException("I/O error: " + e.getMessage(), e))
            .onErrorMap(TimeoutException.class, e ->
                new RemoteTaskException("request timed out", e));
    }

    private void applyHeaders(org.springframework.http.HttpHeaders headers) {
        if (authToken != null && !authToken.isBlank()) {
            headers.setBearerAuth(authToken);
        }
    }

    private String toSubmitBody(TaskRecord task) {
        var node = mapper.createObjectNode();
        node.put("taskId", task.taskId());
        node.put("sessionId", task.sessionId());
        node.put("turnId", task.turnId());
        node.put("input", task.input());
        return writeValue(node);
    }

    private TaskRecord parseTaskRecord(String body, TaskRecord original) {
        try {
            JsonNode json = mapper.readTree(body);
            return new TaskRecord(
                json.path("taskId").asText(original.taskId()),
                json.path("sessionId").asText(original.sessionId()),
                json.path("turnId").asText(original.turnId()),
                parseStatus(json.path("status").asText("QUEUED")),
                original.input(),
                json.path("output").asText(null),
                json.path("error").asText(null),
                original.createdAt(),
                java.time.Instant.now());
        } catch (Exception e) {
            throw new RemoteTaskException("failed to parse response: " + e.getMessage(), e);
        }
    }

    private TaskRecord parseTaskRecordFromResponse(String body) {
        try {
            JsonNode json = mapper.readTree(body);
            return new TaskRecord(
                json.path("taskId").asText(""),
                json.path("sessionId").asText(""),
                json.path("turnId").asText(""),
                parseStatus(json.path("status").asText("QUEUED")),
                json.path("input").asText(null),
                json.path("output").asText(null),
                json.path("error").asText(null),
                parseInstant(json.path("createdAt")),
                parseInstant(json.path("updatedAt")));
        } catch (Exception e) {
            throw new RemoteTaskException("failed to parse response: " + e.getMessage(), e);
        }
    }

    private static TaskStatus parseStatus(String value) {
        try {
            return TaskStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return TaskStatus.QUEUED;
        }
    }

    private static java.time.Instant parseInstant(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        try {
            return java.time.Instant.parse(node.asText());
        } catch (Exception e) {
            return null;
        }
    }

    private String writeValue(com.fasterxml.jackson.databind.node.ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RemoteTaskException("failed to serialize request", e);
        }
    }
}
