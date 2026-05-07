package com.clawcode.agent.api;

import com.clawcode.agent.core.query.QueryEvent;
import com.clawcode.agent.core.session.SessionService;
import com.clawcode.agent.forensics.ObservabilityMetrics;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class StreamController {

    private static final Logger log = LoggerFactory.getLogger(StreamController.class);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final SessionService sessionService;
    private final ObservabilityMetrics metrics;

    public StreamController(SessionService sessionService, ObservabilityMetrics metrics) {
        this.sessionService = sessionService;
        this.metrics = metrics;
    }

    @GetMapping(value = "/api/sessions/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<QueryEvent>> stream(@PathVariable String sessionId) {
        return sessionService.stream(sessionId)
            .map(event -> ServerSentEvent.<QueryEvent>builder().data(event).build())
            .mergeWith(Flux.interval(HEARTBEAT_INTERVAL)
                .map(seq -> ServerSentEvent.<QueryEvent>builder().comment("ping").build()))
            .doOnCancel(() -> {
                log.debug("SSE stream disconnected: session={}", sessionId);
                metrics.recordStreamDisconnect();
            })
            .doOnError(e -> {
                log.warn("SSE stream error: session={}: {}", sessionId, e.getMessage());
                metrics.recordStreamError();
            });
    }
}
