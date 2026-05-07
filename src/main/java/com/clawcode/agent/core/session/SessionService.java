package com.clawcode.agent.core.session;

import com.clawcode.agent.core.query.QueryEvent;
import com.clawcode.agent.core.query.QueryOrchestrator;
import com.clawcode.agent.core.query.TurnCommand;
import com.clawcode.agent.core.query.QueryTranscriptUpdateEvent;
import com.clawcode.agent.persistence.TranscriptStore;
import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.UserMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final int REPLAY_LIMIT = 64;

    private final QueryOrchestrator orchestrator;
    private final TranscriptStore transcriptStore;
    private final SessionRegistry registry;
    private final Map<String, Sinks.Many<QueryEvent>> sessionSinks = new ConcurrentHashMap<>();

    public SessionService(QueryOrchestrator orchestrator,
                          TranscriptStore transcriptStore,
                          SessionRegistry registry) {
        this.orchestrator = orchestrator;
        this.transcriptStore = transcriptStore;
        this.registry = registry;
    }

    public Mono<SessionRecord> create() {
        String sessionId = UUID.randomUUID().toString();
        return registry.register(sessionId)
            .flatMap(record -> transcriptStore.append(sessionId, null, List.of())
                .thenReturn(record));
    }

    public Mono<SessionRecord> get(String sessionId) {
        return registry.find(sessionId);
    }

    public Flux<QueryEvent> submitPrompt(String sessionId, String prompt) {
        return submitPrompt(sessionId, prompt, List.of());
    }

    public Flux<QueryEvent> submitPrompt(String sessionId, String prompt, List<String> skillIds) {
        String turnId = UUID.randomUUID().toString();
        return get(sessionId)
            .switchIfEmpty(Mono.error(() -> new IllegalArgumentException(
                "Session not found: " + sessionId
            )))
            .flatMapMany(record -> transcriptStore.load(sessionId, turnId)
                .flatMapMany(history -> {
                    UserMessage userMessage = new UserMessage(
                        UUID.randomUUID(), Instant.now(), prompt
                    );
                    List<Message> updated = appendMessage(history, userMessage);
                    int persistFromIndex = updated.size();
                    return transcriptStore.append(sessionId, turnId, List.of(userMessage))
                        .thenMany(orchestrator.runTurn(
                            new TurnCommand(turnId, sessionId, updated, "default", null, skillIds, persistFromIndex)
                        ))
                        .concatMap(event -> {
                            if (event instanceof QueryTranscriptUpdateEvent update) {
                                List<Message> delta = update.update().messagesToPersist();
                                if (delta != null && !delta.isEmpty()) {
                                    return transcriptStore.append(sessionId, turnId, delta)
                                        .thenMany(Flux.<QueryEvent>empty());
                                }
                                return Flux.<QueryEvent>empty();
                            }
                            emit(sessionId, event);
                            return Flux.just(event);
                        })
                        .doOnComplete(() ->
                            emit(sessionId, new StreamCompletedEvent(turnId)));
                })
            );
    }

    public Flux<QueryEvent> stream(String sessionId) {
        return registry.find(sessionId)
            .switchIfEmpty(Mono.error(new IllegalArgumentException(
                "Session not found: " + sessionId
            )))
            .thenMany(getOrCreateSink(sessionId).asFlux()
                .doOnCancel(() -> cleanupSink(sessionId)));
    }

    public Mono<TranscriptStore.TranscriptPage> replay(String sessionId, int afterSequence, int limit) {
        return registry.find(sessionId)
            .switchIfEmpty(Mono.error(new IllegalArgumentException(
                "Session not found: " + sessionId
            )))
            .then(transcriptStore.loadPage(sessionId, afterSequence, limit));
    }

    private void emit(String sessionId, QueryEvent event) {
        Sinks.Many<QueryEvent> sink = getOrCreateSink(sessionId);
        EmitResult result = sink.tryEmitNext(event);
        if (result == EmitResult.FAIL_TERMINATED) {
            log.debug("Sink terminated for session={}, replacing", sessionId);
            sessionSinks.remove(sessionId);
        } else if (result == EmitResult.FAIL_OVERFLOW) {
            log.warn("Sink overflow for session={}, dropping event {}", sessionId,
                event.getClass().getSimpleName());
        } else if (result.isFailure()) {
            log.debug("Emit {} for session={}", result, sessionId);
        }
    }

    private void cleanupSink(String sessionId) {
        Sinks.Many<QueryEvent> sink = sessionSinks.get(sessionId);
        if (sink != null && sink.currentSubscriberCount() == 0) {
            sessionSinks.remove(sessionId);
            sink.tryEmitComplete();
        }
    }

    private Sinks.Many<QueryEvent> getOrCreateSink(String sessionId) {
        return sessionSinks.computeIfAbsent(sessionId,
            id -> Sinks.many().replay().limit(REPLAY_LIMIT));
    }

    private List<Message> appendMessage(List<Message> history, Message message) {
        var list = new java.util.ArrayList<>(history);
        list.add(message);
        return List.copyOf(list);
    }

    record StreamCompletedEvent(String turnId) implements QueryEvent {}
}
