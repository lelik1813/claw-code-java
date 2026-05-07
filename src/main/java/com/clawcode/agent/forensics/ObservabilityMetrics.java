package com.clawcode.agent.forensics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class ObservabilityMetrics {

    private final Timer turnLatency;
    private final Counter modelErrors;
    private final Counter toolCalls;
    private final Counter toolErrors;
    private final Counter toolDenied;
    private final Counter toolUnknown;
    private final Counter persistenceAppendErrors;
    private final Counter streamDisconnects;
    private final Counter streamErrors;

    public ObservabilityMetrics(MeterRegistry registry) {
        this.turnLatency = Timer.builder("agent.turn.latency")
            .description("End-to-end turn execution time")
            .register(registry);

        this.modelErrors = Counter.builder("agent.model.errors")
            .description("Model invocation errors")
            .register(registry);

        this.toolCalls = Counter.builder("agent.tool.calls")
            .description("Total tool invocations")
            .register(registry);

        this.toolErrors = Counter.builder("agent.tool.errors")
            .description("Tool execution errors")
            .register(registry);

        this.toolDenied = Counter.builder("agent.tool.denied")
            .description("Tool calls denied by permission policy")
            .register(registry);

        this.toolUnknown = Counter.builder("agent.tool.unknown")
            .description("Unknown tool calls requested by the model")
            .register(registry);

        this.persistenceAppendErrors = Counter.builder("agent.persistence.append.errors")
            .description("Transcript append failures")
            .register(registry);

        this.streamDisconnects = Counter.builder("agent.stream.disconnects")
            .description("SSE stream client disconnects")
            .register(registry);

        this.streamErrors = Counter.builder("agent.stream.errors")
            .description("SSE stream errors")
            .register(registry);
    }

    public Timer.Sample startTurn() {
        return Timer.start();
    }

    public void recordTurnLatency(Timer.Sample sample) {
        sample.stop(turnLatency);
    }

    public void recordModelError() {
        modelErrors.increment();
    }

    public void recordToolCall() {
        toolCalls.increment();
    }

    public void recordToolError() {
        toolErrors.increment();
    }

    public void recordToolDenied() {
        toolDenied.increment();
    }

    public void recordToolUnknown() {
        toolUnknown.increment();
    }

    public void recordPersistenceAppendError() {
        persistenceAppendErrors.increment();
    }

    public void recordStreamDisconnect() {
        streamDisconnects.increment();
    }

    public void recordStreamError() {
        streamErrors.increment();
    }

    public Counter streamDisconnects() {
        return streamDisconnects;
    }

    public Counter streamErrors() {
        return streamErrors;
    }
}
