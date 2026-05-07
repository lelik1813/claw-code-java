package com.clawcode.agent.core.tasks;

import reactor.core.publisher.Mono;

public class TaskService {

    private final TaskExecutor executor;

    public TaskService(TaskExecutor executor) {
        this.executor = executor;
    }

    public Mono<TaskRecord> submit(String sessionId, String turnId, String input) {
        if (sessionId == null || sessionId.isBlank()) {
            return Mono.error(new IllegalArgumentException("sessionId is required"));
        }
        if (input == null || input.isBlank()) {
            return Mono.error(new IllegalArgumentException("input is required"));
        }
        return executor.submit(sessionId, turnId, input);
    }

    public Mono<TaskRecord> getStatus(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Mono.error(new IllegalArgumentException("taskId is required"));
        }
        return executor.getStatus(taskId);
    }

    public Mono<TaskRecord> getResult(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Mono.error(new IllegalArgumentException("taskId is required"));
        }
        return executor.getResult(taskId);
    }

    public Mono<Void> cancel(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Mono.error(new IllegalArgumentException("taskId is required"));
        }
        return executor.cancel(taskId);
    }
}
