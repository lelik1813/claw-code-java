package com.clawcode.agent.core.tasks;

import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Mono;

public class RemoteTaskExecutor implements TaskExecutor {

    private final RemoteTaskClient client;
    private final long pollIntervalMs;

    public RemoteTaskExecutor(RemoteTaskClient client, long pollIntervalMs) {
        this.client = client;
        this.pollIntervalMs = pollIntervalMs;
    }

    @Override
    public Mono<TaskRecord> submit(String sessionId, String turnId, String input) {
        String taskId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        TaskRecord task = new TaskRecord(
            taskId, sessionId, turnId, TaskStatus.QUEUED,
            input, null, null, now, now);
        return client.submitRemote(task);
    }

    @Override
    public Mono<TaskRecord> getStatus(String taskId) {
        return client.fetchStatus(taskId);
    }

    @Override
    public Mono<TaskRecord> getResult(String taskId) {
        return client.fetchResult(taskId);
    }

    @Override
    public Mono<Void> cancel(String taskId) {
        return client.cancelRemote(taskId);
    }
}
