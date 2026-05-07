package com.clawcode.agent.core.session;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SessionRegistry {
    Mono<SessionRecord> register(String sessionId);

    Mono<SessionRecord> find(String sessionId);

    Flux<SessionRecord> listAll();
}
