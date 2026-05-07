package com.clawcode.agent.core.tasks;

import reactor.core.publisher.Mono;

public interface TaskExecutor {

    Mono<TaskRecord> submit(String sessionId, String turnId, String input);

    Mono<TaskRecord> getStatus(String taskId);

    Mono<TaskRecord> getResult(String taskId);

    Mono<Void> cancel(String taskId);
}
