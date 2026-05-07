package com.clawcode.agent.core.tasks;

import reactor.core.publisher.Mono;

public interface RemoteTaskClient {

    Mono<TaskRecord> submitRemote(TaskRecord task);

    Mono<TaskRecord> fetchStatus(String taskId);

    Mono<TaskRecord> fetchResult(String taskId);

    Mono<Void> cancelRemote(String taskId);
}
