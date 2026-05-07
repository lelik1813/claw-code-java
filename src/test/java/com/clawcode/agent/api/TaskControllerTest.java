package com.clawcode.agent.api;

import com.clawcode.agent.core.tasks.*;
import com.clawcode.agent.core.tasks.TaskStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class TaskControllerTest {

    private WebTestClient client;
    private StubTaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = new StubTaskService();
        client = WebTestClient.bindToController(new TaskController(taskService))
            .controllerAdvice(new ApiExceptionHandler())
            .configureClient()
            .responseTimeout(Duration.ofSeconds(5))
            .build();
    }

    // --- submit ---

    @Test
    void submit_returnsAcceptedWithTaskId() {
        client.post().uri("/api/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"sessionId":"s1","input":"do work"}
                """)
            .exchange()
            .expectStatus().isAccepted()
            .expectBody()
            .jsonPath("$.taskId").isNotEmpty()
            .jsonPath("$.status").isEqualTo("QUEUED")
            .jsonPath("$.acceptedAt").isNotEmpty();
    }

    @Test
    void submit_withTurnId_accepted() {
        client.post().uri("/api/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"sessionId":"s1","turnId":"t1","input":"work"}
                """)
            .exchange()
            .expectStatus().isAccepted()
            .expectBody()
            .jsonPath("$.taskId").isNotEmpty();
    }

    @Test
    void submit_blankSessionId_returns400() {
        client.post().uri("/api/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"sessionId":"","input":"work"}
                """)
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void submit_blankInput_returns400() {
        client.post().uri("/api/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"sessionId":"s1","input":""}
                """)
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void submit_missingBody_returns400() {
        client.post().uri("/api/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isBadRequest();
    }

    // --- getStatus ---

    @Test
    void getStatus_existingTask_returns200() {
        String taskId = submitTask("s1", "work");

        client.get().uri("/api/tasks/{taskId}", taskId)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.taskId").isEqualTo(taskId)
            .jsonPath("$.status").isEqualTo("COMPLETED")
            .jsonPath("$.updatedAt").isNotEmpty();
    }

    @Test
    void getStatus_failedTask_includesError() {
        String taskId = taskService.submitFail("s1", "bad input");

        client.get().uri("/api/tasks/{taskId}", taskId)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.taskId").isEqualTo(taskId)
            .jsonPath("$.status").isEqualTo("FAILED")
            .jsonPath("$.error").isEqualTo("something went wrong");
    }

    @Test
    void getStatus_unknownTask_returns404() {
        client.get().uri("/api/tasks/{taskId}", "nonexistent")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.error").value(msg -> assertThat(msg).asString().contains("nonexistent"));
    }

    // --- getResult ---

    @Test
    void getResult_completedTask_returnsOutput() {
        String taskId = submitTask("s1", "compute");

        client.get().uri("/api/tasks/{taskId}/result", taskId)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.taskId").isEqualTo(taskId)
            .jsonPath("$.status").isEqualTo("COMPLETED")
            .jsonPath("$.output").isEqualTo("result: compute");
    }

    @Test
    void getResult_failedTask_returnsError() {
        String taskId = taskService.submitFail("s1", "fail");

        client.get().uri("/api/tasks/{taskId}/result", taskId)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("FAILED")
            .jsonPath("$.error").isEqualTo("something went wrong")
            .jsonPath("$.output").isEmpty();
    }

    @Test
    void getResult_unknownTask_returns404() {
        client.get().uri("/api/tasks/{taskId}/result", "missing")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.error").value(msg -> assertThat(msg).asString().contains("missing"));
    }

    // --- cancel ---

    @Test
    void cancel_existingTask_returnsAccepted() {
        String taskId = submitTask("s1", "work");

        client.post().uri("/api/tasks/{taskId}/cancel", taskId)
            .exchange()
            .expectStatus().isAccepted();
    }

    @Test
    void cancel_setsStatusCancelled() {
        String taskId = submitTask("s1", "work");

        client.post().uri("/api/tasks/{taskId}/cancel", taskId)
            .exchange()
            .expectStatus().isAccepted();

        client.get().uri("/api/tasks/{taskId}", taskId)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("CANCELLED");
    }

    // --- helpers ---

    private String submitTask(String sessionId, String input) {
        return taskService.submitCompleted(sessionId, input);
    }

    private static class StubTaskService extends TaskService {

        private final java.util.Map<String, TaskRecord> tasks = new java.util.concurrent.ConcurrentHashMap<>();
        private java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);

        StubTaskService() {
            super(null);
        }

        String submitCompleted(String sessionId, String input) {
            String taskId = "task-" + counter.incrementAndGet();
            Instant now = Instant.now();
            TaskRecord task = new TaskRecord(taskId, sessionId, null, TaskStatus.COMPLETED,
                input, "result: " + input, null, now, now);
            tasks.put(taskId, task);
            return taskId;
        }

        String submitFail(String sessionId, String input) {
            String taskId = "task-" + counter.incrementAndGet();
            Instant now = Instant.now();
            TaskRecord task = new TaskRecord(taskId, sessionId, null, TaskStatus.FAILED,
                input, null, "something went wrong", now, now);
            tasks.put(taskId, task);
            return taskId;
        }

        @Override
        public Mono<TaskRecord> submit(String sessionId, String turnId, String input) {
            if (sessionId == null || sessionId.isBlank()) {
                return Mono.error(new IllegalArgumentException("sessionId is required"));
            }
            if (input == null || input.isBlank()) {
                return Mono.error(new IllegalArgumentException("input is required"));
            }
            String taskId = "task-" + counter.incrementAndGet();
            Instant now = Instant.now();
            TaskRecord task = new TaskRecord(taskId, sessionId, turnId, TaskStatus.QUEUED,
                input, null, null, now, now);
            tasks.put(taskId, task);
            return Mono.just(task);
        }

        @Override
        public Mono<TaskRecord> getStatus(String taskId) {
            TaskRecord task = tasks.get(taskId);
            if (task == null) return Mono.error(new TaskNotFoundException(taskId));
            return Mono.just(task);
        }

        @Override
        public Mono<TaskRecord> getResult(String taskId) {
            TaskRecord task = tasks.get(taskId);
            if (task == null) return Mono.error(new TaskNotFoundException(taskId));
            return Mono.just(task);
        }

        @Override
        public Mono<Void> cancel(String taskId) {
            TaskRecord task = tasks.get(taskId);
            if (task != null) {
                tasks.put(taskId, task.withStatus(TaskStatus.CANCELLED));
            }
            return Mono.empty();
        }
    }
}
