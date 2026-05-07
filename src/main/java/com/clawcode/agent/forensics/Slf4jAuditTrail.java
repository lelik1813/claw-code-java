package com.clawcode.agent.forensics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class Slf4jAuditTrail implements AuditTrail {

    private static final Logger log = LoggerFactory.getLogger(Slf4jAuditTrail.class);

    @Override
    public Mono<Void> emit(AuditEvent event) {
        return Mono.fromRunnable(() ->
            log.info("type={} session={} turn={} ts={} attrs={}",
                event.eventType(),
                event.sessionId(),
                event.turnId(),
                event.timestamp(),
                event.attributes()));
    }
}
