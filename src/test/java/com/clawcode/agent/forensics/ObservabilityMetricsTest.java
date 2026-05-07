package com.clawcode.agent.forensics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ObservabilityMetrics metrics = new ObservabilityMetrics(registry);

    @Test
    void turnLatencyRecordedOnSuccess() {
        var sample = metrics.startTurn();
        metrics.recordTurnLatency(sample);

        assertThat(registry.timer("agent.turn.latency").count()).isEqualTo(1);
        assertThat(registry.timer("agent.turn.latency").totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
            .isGreaterThanOrEqualTo(0);
    }

    @Test
    void turnLatencyRecordedOnError() {
        var sample = metrics.startTurn();
        metrics.recordTurnLatency(sample);

        assertThat(registry.timer("agent.turn.latency").count()).isEqualTo(1);
    }

    @Test
    void modelErrorCounterIncrements() {
        assertThat(registry.counter("agent.model.errors").count()).isZero();

        metrics.recordModelError();
        assertThat(registry.counter("agent.model.errors").count()).isEqualTo(1);

        metrics.recordModelError();
        assertThat(registry.counter("agent.model.errors").count()).isEqualTo(2);
    }

    @Test
    void toolCallCounterIncrementsOnAllow() {
        metrics.recordToolCall();

        assertThat(registry.counter("agent.tool.calls").count()).isEqualTo(1);
        assertThat(registry.counter("agent.tool.denied").count()).isZero();
    }

    @Test
    void toolDeniedCounterIncrements() {
        metrics.recordToolDenied();

        assertThat(registry.counter("agent.tool.denied").count()).isEqualTo(1);
        assertThat(registry.counter("agent.tool.calls").count()).isZero();
    }

    @Test
    void toolUnknownCounterIncrements() {
        assertThat(registry.counter("agent.tool.unknown").count()).isZero();

        metrics.recordToolUnknown();

        assertThat(registry.counter("agent.tool.unknown").count()).isEqualTo(1);
        assertThat(registry.counter("agent.tool.denied").count()).isZero();
        assertThat(registry.counter("agent.tool.calls").count()).isZero();

        var meter = registry.find("agent.tool.unknown").meter();
        assertThat(meter).isNotNull();
        assertThat(meter.getId().getDescription()).isEqualTo("Unknown tool calls requested by the model");
    }

    @Test
    void toolErrorCounterIncrements() {
        metrics.recordToolError();

        assertThat(registry.counter("agent.tool.errors").count()).isEqualTo(1);
    }

    @Test
    void persistenceAppendErrorCounterIncrements() {
        metrics.recordPersistenceAppendError();

        assertThat(registry.counter("agent.persistence.append.errors").count()).isEqualTo(1);
    }

    @Test
    void fullTurnPathRecordsCorrectMetrics() {
        var sample = metrics.startTurn();
        metrics.recordToolCall();
        metrics.recordTurnLatency(sample);

        assertThat(registry.timer("agent.turn.latency").count()).isEqualTo(1);
        assertThat(registry.counter("agent.tool.calls").count()).isEqualTo(1);
        assertThat(registry.counter("agent.tool.errors").count()).isZero();
        assertThat(registry.counter("agent.tool.denied").count()).isZero();
    }

    @Test
    void denyPathRecordsToolDeniedNotToolCall() {
        metrics.recordToolDenied();

        assertThat(registry.counter("agent.tool.denied").count()).isEqualTo(1);
        assertThat(registry.counter("agent.tool.calls").count()).isZero();
        assertThat(registry.counter("agent.tool.errors").count()).isZero();
    }

    @Test
    void errorPathRecordsToolError() {
        metrics.recordToolCall();
        metrics.recordToolError();

        assertThat(registry.counter("agent.tool.calls").count()).isEqualTo(1);
        assertThat(registry.counter("agent.tool.errors").count()).isEqualTo(1);
    }
}
