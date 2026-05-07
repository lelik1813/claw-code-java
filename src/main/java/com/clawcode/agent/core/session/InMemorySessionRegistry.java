package com.clawcode.agent.core.session;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class InMemorySessionRegistry implements SessionRegistry {

    private final Map<String, SessionRecord> sessions = new ConcurrentHashMap<>();

    @Override
    public Mono<SessionRecord> register(String sessionId) {
        SessionRecord record = new SessionRecord(sessionId, Instant.now());
        sessions.put(sessionId, record);
        return Mono.just(record);
    }

    @Override
    public Mono<SessionRecord> find(String sessionId) {
        return Mono.justOrEmpty(sessions.get(sessionId));
    }

    @Override
    public Flux<SessionRecord> listAll() {
        return Flux.fromIterable(sessions.values());
    }
}
