package com.clawcode.agent.persistence;

import com.clawcode.agent.forensics.AuditEvent;
import com.clawcode.agent.forensics.AuditTrail;
import com.clawcode.agent.shared.message.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;

public class InMemoryTranscriptStore implements TranscriptStore {

    private final Map<String, List<Message>> transcripts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> sequenceCounters = new ConcurrentHashMap<>();
    private final AuditTrail auditTrail;

    public InMemoryTranscriptStore(AuditTrail auditTrail) {
        this.auditTrail = auditTrail;
    }

    @Override
    public Mono<List<Message>> load(String sessionId, String turnId) {
        Map<String, Object> startAttrs = new LinkedHashMap<>();
        startAttrs.put("messageCount", 0);
        return audit("transcript.load.start", sessionId, turnId, startAttrs)
            .then(Mono.fromSupplier(() ->
                transcripts.getOrDefault(sessionId, Collections.emptyList())))
            .flatMap(messages -> {
                Map<String, Object> endAttrs = new LinkedHashMap<>();
                endAttrs.put("messageCount", messages.size());
                return audit("transcript.load.end", sessionId, turnId, endAttrs)
                    .thenReturn(messages);
            });
    }

    @Override
    public Mono<Void> append(String sessionId, String turnId, List<Message> messages) {
        Map<String, Object> startAttrs = new LinkedHashMap<>();
        startAttrs.put("messageCount", messages.size());
        return audit("transcript.append.start", sessionId, turnId, startAttrs)
            .then(Mono.fromRunnable(() -> {
                List<Message> safeIncoming = List.copyOf(messages);
                transcripts.merge(sessionId, safeIncoming, (existing, incoming) -> {
                    var combined = new ArrayList<>(existing);
                    combined.addAll(incoming);
                    return List.copyOf(combined);
                });
                sequenceCounters.computeIfAbsent(sessionId, id -> new AtomicInteger(0))
                    .addAndGet(messages.size());
            }))
            .then(audit("transcript.append.end", sessionId, turnId, startAttrs));
    }

    @Override
    public Mono<TranscriptPage> loadPage(String sessionId, int afterSequence, int limit) {
        return Mono.fromSupplier(() -> {
            List<Message> all = transcripts.getOrDefault(sessionId, Collections.emptyList());
            int start = Math.max(0, afterSequence);
            int end = Math.min(all.size(), start + limit + 1);
            List<Message> slice = all.subList(start, end);

            boolean hasMore = slice.size() > limit;
            List<Message> page = hasMore ? slice.subList(0, limit) : slice;

            List<SequencedMessage> sequenced = new ArrayList<>(page.size());
            for (int i = 0; i < page.size(); i++) {
                sequenced.add(new SequencedMessage(start + i + 1, page.get(i)));
            }

            int nextCursor = sequenced.isEmpty() ? afterSequence
                : sequenced.getLast().sequenceNo();
            return new TranscriptPage(sequenced, nextCursor, hasMore);
        });
    }

    private Mono<Void> audit(String eventType, String sessionId, String turnId, Map<String, Object> attributes) {
        return auditTrail.emit(AuditEvent.of(eventType, sessionId, turnId, attributes));
    }
}
