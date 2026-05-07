package com.clawcode.agent.core.tasks;

import java.time.Instant;

public record TaskRecord(
    String taskId,
    String sessionId,
    String turnId,
    TaskStatus status,
    String input,
    String output,
    String error,
    Instant createdAt,
    Instant updatedAt
) {

    public TaskRecord withStatus(TaskStatus status) {
        return new TaskRecord(taskId, sessionId, turnId, status,
            input, output, error, createdAt, Instant.now());
    }

    public TaskRecord withOutput(String output) {
        return new TaskRecord(taskId, sessionId, turnId, status,
            input, output, error, createdAt, Instant.now());
    }

    public TaskRecord withError(String error) {
        return new TaskRecord(taskId, sessionId, turnId, TaskStatus.FAILED,
            input, output, error, createdAt, Instant.now());
    }
}
