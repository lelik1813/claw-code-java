package com.clawcode.agent.core.tasks;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTaskExecutorTest {

    // --- submit -> completed ---

    @Test
    void submit_executesAndCompletes() {
        var executor = new InMemoryTaskExecutor(input -> Mono.just("result: " + input));

        TaskRecord submitted = executor.submit("s1", "t1", "hello").block();

        assertThat(submitted).isNotNull();
        assertThat(submitted.taskId()).isNotBlank();
        assertThat(submitted.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(submitted.sessionId()).isEqualTo("s1");
        assertThat(submitted.input()).isEqualTo("hello");

        TaskRecord result = executor.getResult(submitted.taskId())
            .block(Duration.ofSeconds(5));

        assertThat(result.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(result.output()).isEqualTo("result: hello");
    }

    @Test
    void submit_preservesSessionAndTurnId() {
        var executor = new InMemoryTaskExecutor(input -> Mono.just("ok"));

        TaskRecord submitted = executor.submit("session-42", "turn-7", "data").block();
        TaskRecord result = executor.getResult(submitted.taskId())
            .block(Duration.ofSeconds(5));

        assertThat(result.sessionId()).isEqualTo("session-42");
        assertThat(result.turnId()).isEqualTo("turn-7");
    }

    @Test
    void submit_createdAtIsSet() {
        var executor = new InMemoryTaskExecutor(input -> Mono.just("ok"));

        TaskRecord submitted = executor.submit("s1", null, "x").block();

        assertThat(submitted.createdAt()).isNotNull();
        assertThat(submitted.createdAt()).isBeforeOrEqualTo(Instant.now());
    }

    // --- failure path ---

    @Test
    void submit_executionFails_statusIsFailed() {
        var executor = new InMemoryTaskExecutor(
            input -> Mono.error(new RuntimeException("boom")));

        TaskRecord submitted = executor.submit("s1", null, "fail-input").block();
        TaskRecord result = executor.getResult(submitted.taskId())
            .block(Duration.ofSeconds(5));

        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.error()).isEqualTo("boom");
    }

    @Test
    void submit_executionFailsWithNullMessage_usesClassName() {
        var executor = new InMemoryTaskExecutor(
            input -> Mono.error(new RuntimeException()));

        TaskRecord submitted = executor.submit("s1", null, "x").block();
        TaskRecord result = executor.getResult(submitted.taskId())
            .block(Duration.ofSeconds(5));

        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.error()).isEqualTo("RuntimeException");
    }

    // --- getStatus ---

    @Test
    void getStatus_returnsCurrentState() {
        var executor = new InMemoryTaskExecutor(input -> Mono.just("ok"));

        TaskRecord submitted = executor.submit("s1", null, "x").block();
        TaskRecord status = executor.getStatus(submitted.taskId()).block();

        assertThat(status.taskId()).isEqualTo(submitted.taskId());
    }

    @Test
    void getStatus_unknownTask_throwsNotFound() {
        var executor = new InMemoryTaskExecutor(input -> Mono.just("ok"));

        StepVerifier.create(executor.getStatus("nonexistent"))
            .expectErrorMatches(e -> e instanceof TaskNotFoundException
                && e.getMessage().contains("nonexistent"))
            .verify();
    }

    // --- getResult ---

    @Test
    void getResult_completedTask_returnsImmediately() {
        var executor = new InMemoryTaskExecutor(input -> Mono.just("done"));

        TaskRecord submitted = executor.submit("s1", null, "x").block();
        executor.getResult(submitted.taskId()).block(Duration.ofSeconds(5));

        TaskRecord result = executor.getResult(submitted.taskId())
            .block(Duration.ofSeconds(1));

        assertThat(result.status()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void getResult_unknownTask_throwsNotFound() {
        var executor = new InMemoryTaskExecutor(input -> Mono.just("ok"));

        StepVerifier.create(executor.getResult("missing"))
            .expectErrorMatches(e -> e instanceof TaskNotFoundException)
            .verify();
    }

    // --- cancel ---

    @Test
    void cancel_runningTask_setsCancelled() {
        var executor = new InMemoryTaskExecutor(
            input -> Mono.delay(Duration.ofSeconds(30)).map(t -> "late"));

        TaskRecord submitted = executor.submit("s1", null, "slow").block();

        executor.cancel(submitted.taskId()).block();

        TaskRecord status = executor.getStatus(submitted.taskId()).block();
        assertThat(status.status()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void cancel_alreadyCompleted_isIdempotent() {
        var executor = new InMemoryTaskExecutor(input -> Mono.just("fast"));

        TaskRecord submitted = executor.submit("s1", null, "x").block();
        executor.getResult(submitted.taskId()).block(Duration.ofSeconds(5));

        executor.cancel(submitted.taskId()).block();

        TaskRecord status = executor.getStatus(submitted.taskId()).block();
        assertThat(status.status()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void cancel_unknownTask_doesNotThrow() {
        var executor = new InMemoryTaskExecutor(input -> Mono.just("ok"));

        StepVerifier.create(executor.cancel("nonexistent"))
            .verifyComplete();
    }

    // --- multiple tasks ---

    @Test
    void multipleTasks_executeIndependently() {
        var executor = new InMemoryTaskExecutor(input -> Mono.just("result: " + input));

        TaskRecord t1 = executor.submit("s1", null, "input-1").block();
        TaskRecord t2 = executor.submit("s1", null, "input-2").block();

        assertThat(t1.taskId()).isNotEqualTo(t2.taskId());

        TaskRecord r1 = executor.getResult(t1.taskId()).block(Duration.ofSeconds(5));
        TaskRecord r2 = executor.getResult(t2.taskId()).block(Duration.ofSeconds(5));

        assertThat(r1.output()).isEqualTo("result: input-1");
        assertThat(r2.output()).isEqualTo("result: input-2");
    }

    @Test
    void cancelOneTask_otherTasksUnaffected() {
        var executor = new InMemoryTaskExecutor(
            input -> Mono.delay(Duration.ofMillis(100)).then(Mono.just("ok")));

        TaskRecord t1 = executor.submit("s1", null, "fast").block();
        TaskRecord t2 = executor.submit("s1", null, "slow").block();

        executor.cancel(t2.taskId()).block();

        TaskRecord r1 = executor.getResult(t1.taskId()).block(Duration.ofSeconds(5));
        assertThat(r1.status()).isEqualTo(TaskStatus.COMPLETED);

        TaskRecord r2 = executor.getStatus(t2.taskId()).block();
        assertThat(r2.status()).isEqualTo(TaskStatus.CANCELLED);
    }
}
