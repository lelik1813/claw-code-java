package com.clawcode.agent.core.tasks;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Schedulers;

public class InMemoryTaskExecutor implements TaskExecutor {

    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final Map<String, MonoSink<TaskRecord>> pending = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelled = new ConcurrentHashMap<>();
    private final Function<String, Mono<String>> executionFn;

    public InMemoryTaskExecutor(Function<String, Mono<String>> executionFn) {
        this.executionFn = executionFn;
    }

    @Override
    public Mono<TaskRecord> submit(String sessionId, String turnId, String input) {
        return Mono.fromCallable(() -> {
            String taskId = UUID.randomUUID().toString();
            Instant now = Instant.now();
            TaskRecord task = new TaskRecord(
                taskId, sessionId, turnId, TaskStatus.QUEUED,
                input, null, null, now, now);
            tasks.put(taskId, task);
            cancelled.put(taskId, new AtomicBoolean(false));
            return task;
        }).flatMap(task -> {
            executeAsync(task);
            return Mono.just(task);
        });
    }

    @Override
    public Mono<TaskRecord> getStatus(String taskId) {
        return Mono.fromCallable(() -> tasks.get(taskId))
            .switchIfEmpty(Mono.error(new TaskNotFoundException(taskId)));
    }

    @Override
    public Mono<TaskRecord> getResult(String taskId) {
        return Mono.<TaskRecord>create(sink -> {
            TaskRecord task = tasks.get(taskId);
            if (task == null) {
                sink.error(new TaskNotFoundException(taskId));
                return;
            }
            if (task.status() == TaskStatus.COMPLETED
                || task.status() == TaskStatus.FAILED
                || task.status() == TaskStatus.CANCELLED) {
                sink.success(task);
                return;
            }
            pending.put(taskId, sink);
            sink.onDispose(() -> pending.remove(taskId));
        });
    }

    @Override
    public Mono<Void> cancel(String taskId) {
        return Mono.fromRunnable(() -> {
            AtomicBoolean flag = cancelled.get(taskId);
            if (flag != null) flag.set(true);

            TaskRecord task = tasks.get(taskId);
            if (task != null && (task.status() == TaskStatus.QUEUED
                || task.status() == TaskStatus.RUNNING)) {
                TaskRecord cancelledTask = task.withStatus(TaskStatus.CANCELLED);
                tasks.put(taskId, cancelledTask);
                completePending(taskId, cancelledTask);
            }
        });
    }

    private void executeAsync(TaskRecord task) {
        Mono.fromCallable(() -> {
                AtomicBoolean flag = cancelled.get(task.taskId());
                if (flag != null && flag.get()) {
                    return task.withStatus(TaskStatus.CANCELLED);
                }
                TaskRecord running = task.withStatus(TaskStatus.RUNNING);
                tasks.put(task.taskId(), running);
                return running;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(running -> {
                AtomicBoolean flag = cancelled.get(running.taskId());
                if (flag != null && flag.get()) {
                    return Mono.just(running.withStatus(TaskStatus.CANCELLED));
                }
                return executionFn.apply(running.input())
                    .map(running::withOutput)
                    .onErrorResume(e -> Mono.just(
                        running.withError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
            })
            .subscribe(result -> {
                AtomicBoolean flag = cancelled.get(result.taskId());
                if (flag != null && flag.get()
                    && result.status() != TaskStatus.CANCELLED) {
                    return;
                }
                TaskRecord final_ = result.status() == TaskStatus.RUNNING
                    ? result.withStatus(TaskStatus.COMPLETED)
                    : result;
                tasks.put(final_.taskId(), final_);
                completePending(final_.taskId(), final_);
            });
    }

    private void completePending(String taskId, TaskRecord task) {
        MonoSink<TaskRecord> sink = pending.remove(taskId);
        if (sink != null) sink.success(task);
    }
}
