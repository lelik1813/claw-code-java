package com.clawcode.agent.forensics;

import reactor.core.publisher.Mono;

public interface AuditTrail {

    Mono<Void> emit(AuditEvent event);
}
