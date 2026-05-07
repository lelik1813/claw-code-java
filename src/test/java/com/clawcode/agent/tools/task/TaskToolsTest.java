package com.clawcode.agent.tools.task;

import com.clawcode.agent.core.tasks.*;
import com.clawcode.agent.core.tasks.TaskStatus;
import com.clawcode.agent.forensics.AuditTrail;
import com.clawcode.agent.forensics.ObservabilityMetrics;
import com.clawcode.agent.tools.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class TaskToolsTest {

    private static final AuditTrail noopAudit = event -> Mono.empty();
    private static final ObservabilityMetrics noopMetrics = new ObservabilityMetrics(new SimpleMeterRegistry());

    private StubTaskService taskService;
    private TaskSubmitTool submitTool;
    private TaskStatusTool statusTool;
    private TaskResultTool resultTool;

    @BeforeEach
    void setUp() {
        taskService = new StubTaskService();
        submitTool = new TaskSubmitTool(taskService);
        statusTool = new TaskStatusTool(taskService);
        resultTool = new TaskResultTool(taskService);
    }

    // --- task_submit ---

    @Test
    void submit_happyPath_returnsTaskIdAndStatus() {
        StepVerifier.create(submitTool.execute(
            Map.of("session_id", "s1", "input", "do work"), null))
            .assertNext(obj -> {
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) obj;
                assertThat(map).containsEntry("status", "COMPLETED");
                assertThat(map.get("task_id")).asString().isNotBlank();
            })
            .verifyComplete();
    }

    @Test
    void submit_withTurnId_accepted() {
        StepVerifier.create(submitTool.execute(
            Map.of("session_id", "s1", "turn_id", "t1", "input", "work"), null))
            .assertNext(obj -> {
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) obj;
                assertThat(map.get("task_id")).asString().isNotBlank();
            })
            .verifyComplete();
    }

    @Test
    void submit_missingSessionId_returnsError() {
        StepVerifier.create(submitTool.execute(
            Map.of("input", "work"), null))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().contains("session_id is required"))
            .verify();
    }

    @Test
    void submit_blankSessionId_returnsError() {
        StepVerifier.create(submitTool.execute(
            Map.of("session_id", "", "input", "work"), null))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().contains("session_id is required"))
            .verify();
    }

    @Test
    void submit_missingInput_returnsError() {
        StepVerifier.create(submitTool.execute(
            Map.of("session_id", "s1"), null))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().contains("input is required"))
            .verify();
    }

    @Test
    void submit_blankInput_returnsError() {
        StepVerifier.create(submitTool.execute(
            Map.of("session_id", "s1", "input", "  "), null))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().contains("input is required"))
            .verify();
    }

    @Test
    void submit_nullInput_returnsError() {
        StepVerifier.create(submitTool.execute(null, null))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().contains("session_id is required"))
            .verify();
    }

    // --- task_status ---

    @Test
    void status_completedTask_returnsStatus() {
        String taskId = taskService.putCompleted("s1", "work");

        StepVerifier.create(statusTool.execute(
            Map.of("task_id", taskId), null))
            .assertNext(obj -> {
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) obj;
                assertThat(map).containsEntry("task_id", taskId);
                assertThat(map).containsEntry("status", "COMPLETED");
            })
            .verifyComplete();
    }

    @Test
    void status_failedTask_includesError() {
        String taskId = taskService.putFailed("s1", "bad");

        StepVerifier.create(statusTool.execute(
            Map.of("task_id", taskId), null))
            .assertNext(obj -> {
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) obj;
                assertThat(map).containsEntry("status", "FAILED");
                assertThat(map).containsEntry("error", "something went wrong");
            })
            .verifyComplete();
    }

    @Test
    void status_runningTask_returnsRunning() {
        String taskId = taskService.put("s1", null, "run", TaskStatus.RUNNING);

        StepVerifier.create(statusTool.execute(
            Map.of("task_id", taskId), null))
            .assertNext(obj -> {
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) obj;
                assertThat(map).containsEntry("status", "RUNNING");
            })
            .verifyComplete();
    }

    @Test
    void status_unknownTask_propagatesError() {
        StepVerifier.create(statusTool.execute(
            Map.of("task_id", "missing"), null))
            .expectErrorMatches(e -> e instanceof TaskNotFoundException)
            .verify();
    }

    @Test
    void status_missingTaskId_returnsError() {
        StepVerifier.create(statusTool.execute(Map.of(), null))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().contains("task_id is required"))
            .verify();
    }

    // --- task_result ---

    @Test
    void result_completedTask_returnsOutput() {
        String taskId = taskService.putCompleted("s1", "compute");

        StepVerifier.create(resultTool.execute(
            Map.of("task_id", taskId), null))
            .assertNext(obj -> {
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) obj;
                assertThat(map).containsEntry("task_id", taskId);
                assertThat(map).containsEntry("status", "COMPLETED");
                assertThat(map).containsEntry("output", "result: compute");
            })
            .verifyComplete();
    }

    @Test
    void result_failedTask_returnsError() {
        String taskId = taskService.putFailed("s1", "fail");

        StepVerifier.create(resultTool.execute(
            Map.of("task_id", taskId), null))
            .assertNext(obj -> {
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) obj;
                assertThat(map).containsEntry("status", "FAILED");
                assertThat(map).containsEntry("error", "something went wrong");
                assertThat(map.get("output")).isNull();
            })
            .verifyComplete();
    }

    @Test
    void result_unknownTask_propagatesError() {
        StepVerifier.create(resultTool.execute(
            Map.of("task_id", "nonexistent"), null))
            .expectErrorMatches(e -> e instanceof TaskNotFoundException)
            .verify();
    }

    @Test
    void result_missingTaskId_returnsError() {
        StepVerifier.create(resultTool.execute(Map.of(), null))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().contains("task_id is required"))
            .verify();
    }

    // --- Integration through DefaultToolExecutor ---

    @Test
    void submit_throughExecutor_success() {
        var registry = registryOf(submitTool);
        var executor = new DefaultToolExecutor(registry, allowAll(), noopAudit, noopMetrics, List.of());

        var request = new ToolUseRequest("c1", "task_submit",
            Map.of("session_id", "s1", "input", "work"));

        StepVerifier.create(executor.execute(request, ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isFalse();
                assertThat(r.output()).isInstanceOf(Map.class);
            })
            .verifyComplete();
    }

    @Test
    void status_throughExecutor_success() {
        String taskId = taskService.putCompleted("s1", "work");
        var registry = registryOf(statusTool);
        var executor = new DefaultToolExecutor(registry, allowAll(), noopAudit, noopMetrics, List.of());

        var request = new ToolUseRequest("c2", "task_status",
            Map.of("task_id", taskId));

        StepVerifier.create(executor.execute(request, ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isFalse();
                assertThat(r.output()).isInstanceOf(Map.class);
            })
            .verifyComplete();
    }

    @Test
    void result_throughExecutor_errorBecomesToolResultError() {
        var registry = registryOf(resultTool);
        var executor = new DefaultToolExecutor(registry, allowAll(), noopAudit, noopMetrics, List.of());

        var request = new ToolUseRequest("c3", "task_result",
            Map.of("task_id", "nonexistent"));

        StepVerifier.create(executor.execute(request, ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.errorMessage()).contains("nonexistent");
            })
            .verifyComplete();
    }

    @Test
    void submit_missingParams_throughExecutor_givesToolResultError() {
        var registry = registryOf(submitTool);
        var executor = new DefaultToolExecutor(registry, allowAll(), noopAudit, noopMetrics, List.of());

        var request = new ToolUseRequest("c4", "task_submit", Map.of());

        StepVerifier.create(executor.execute(request, ctx()))
            .assertNext(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.errorMessage()).contains("session_id is required");
            })
            .verifyComplete();
    }

    // === Helpers ===

    private static ToolPermissionPolicy allowAll() {
        return (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow());
    }

    private static ToolExecutionContext ctx() {
        return new ToolExecutionContext("t", "s", "m", null);
    }

    private static ToolRegistry registryOf(Tool... tools) {
        return new ToolRegistry() {
            @Override
            public java.util.Optional<Tool> findByName(String name) {
                for (Tool t : tools) {
                    if (t.name().equals(name)) return java.util.Optional.of(t);
                }
                return java.util.Optional.empty();
            }
            @Override
            public java.util.Set<String> listNames() {
                return java.util.Set.of();
            }
        };
    }

    private static class StubTaskService extends TaskService {

        private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();
        private int counter = 0;

        StubTaskService() {
            super(null);
        }

        String putCompleted(String sessionId, String input) {
            return put(sessionId, null, input, TaskStatus.COMPLETED);
        }

        String putFailed(String sessionId, String input) {
            String taskId = nextId();
            Instant now = Instant.now();
            tasks.put(taskId, new TaskRecord(taskId, sessionId, null, TaskStatus.FAILED,
                input, null, "something went wrong", now, now));
            return taskId;
        }

        String put(String sessionId, String turnId, String input, TaskStatus status) {
            String taskId = nextId();
            Instant now = Instant.now();
            tasks.put(taskId, new TaskRecord(taskId, sessionId, turnId, status,
                input, "result: " + input, null, now, now));
            return taskId;
        }

        private String nextId() {
            return "task-" + (++counter);
        }

        @Override
        public Mono<TaskRecord> submit(String sessionId, String turnId, String input) {
            if (sessionId == null || sessionId.isBlank()) {
                return Mono.error(new IllegalArgumentException("session_id is required"));
            }
            if (input == null || input.isBlank()) {
                return Mono.error(new IllegalArgumentException("input is required"));
            }
            String taskId = putCompleted(sessionId, input);
            return Mono.just(tasks.get(taskId));
        }

        @Override
        public Mono<TaskRecord> getStatus(String taskId) {
            if (taskId == null || taskId.isBlank()) {
                return Mono.error(new IllegalArgumentException("taskId is required"));
            }
            TaskRecord task = tasks.get(taskId);
            if (task == null) return Mono.error(new TaskNotFoundException(taskId));
            return Mono.just(task);
        }

        @Override
        public Mono<TaskRecord> getResult(String taskId) {
            if (taskId == null || taskId.isBlank()) {
                return Mono.error(new IllegalArgumentException("taskId is required"));
            }
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
