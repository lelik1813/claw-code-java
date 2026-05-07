package com.clawcode.agent.persistence;

import com.clawcode.agent.forensics.AuditEvent;
import com.clawcode.agent.forensics.AuditTrail;
import com.clawcode.agent.forensics.ObservabilityMetrics;
import com.clawcode.agent.persistence.postgres.MessageRow;
import com.clawcode.agent.shared.message.Message;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
public class PostgresTranscriptStore implements TranscriptStore {

    private static final int MAX_APPEND_ATTEMPTS = 3;

    private final R2dbcEntityTemplate template;
    private final TransactionalOperator txOp;
    private final AuditTrail auditTrail;
    private final ObservabilityMetrics metrics;

    public PostgresTranscriptStore(R2dbcEntityTemplate template, TransactionalOperator txOp,
                                   AuditTrail auditTrail, ObservabilityMetrics metrics) {
        this.template = template;
        this.txOp = txOp;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
    }

    @Override
    public Mono<List<Message>> load(String sessionId, String turnId) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("messageCount", 0);
        return audit("transcript.load.start", sessionId, turnId, attrs)
            .then(doLoad(sessionId))
            .flatMap(messages -> {
                Map<String, Object> endAttrs = new LinkedHashMap<>();
                endAttrs.put("messageCount", messages.size());
                return audit("transcript.load.end", sessionId, turnId, endAttrs)
                    .thenReturn(messages);
            });
    }

    private Mono<List<Message>> doLoad(String sessionId) {
        UUID sessionUuid = UUID.fromString(sessionId);
        return template.select(MessageRow.class)
            .matching(Query.query(
                Criteria.where("session_id").is(sessionUuid)
            ).sort(Sort.by("sequence_no")))
            .all()
            .map(MessageRow::toMessage)
            .collectList();
    }

    @Override
    public Mono<Void> append(String sessionId, String turnId, List<Message> messages) {
        if (messages.isEmpty()) {
            return Mono.empty();
        }
        Map<String, Object> startAttrs = new LinkedHashMap<>();
        startAttrs.put("messageCount", messages.size());
        UUID sessionUuid = UUID.fromString(sessionId);
        return audit("transcript.append.start", sessionId, turnId, startAttrs)
            .then(appendWithRetry(sessionId, turnId, sessionUuid, messages, 1));
    }

    private Mono<Void> appendWithRetry(String sessionId, String turnId, UUID sessionUuid,
                                        List<Message> messages, int attempt) {
        Map<String, Object> endAttrs = new LinkedHashMap<>();
        endAttrs.put("messageCount", messages.size());
        endAttrs.put("attempt", attempt);
        return appendOnce(sessionUuid, messages)
            .then(audit("transcript.append.end", sessionId, turnId, endAttrs))
            .onErrorResume(e -> {
                if (!isUniqueViolation(e)) {
                    metrics.recordPersistenceAppendError();
                    Map<String, Object> errAttrs = new LinkedHashMap<>();
                    errAttrs.put("messageCount", messages.size());
                    errAttrs.put("attempt", attempt);
                    errAttrs.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                    return audit("transcript.append.error", sessionId, turnId, errAttrs)
                        .then(Mono.error(e));
                }
                if (attempt >= MAX_APPEND_ATTEMPTS) {
                    metrics.recordPersistenceAppendError();
                    log.warn("Unique-sequence conflict persisted after {} attempts for session {}",
                        MAX_APPEND_ATTEMPTS, sessionUuid);
                    Map<String, Object> errAttrs = new LinkedHashMap<>();
                    errAttrs.put("messageCount", messages.size());
                    errAttrs.put("attempt", attempt);
                    errAttrs.put("error", "unique-sequence conflict after max retries");
                    return audit("transcript.append.error", sessionId, turnId, errAttrs)
                        .then(Mono.error(e));
                }
                return appendWithRetry(sessionId, turnId, sessionUuid, messages, attempt + 1);
            });
    }

    private Mono<Void> appendOnce(UUID sessionUuid, List<Message> messages) {
        return txOp.transactional(
            nextSequenceNo(sessionUuid)
                .flatMapMany(start -> Flux.fromIterable(messages)
                    .index((idx, msg) -> MessageRow.from(sessionUuid, msg, start + idx.intValue())))
                .flatMap(row -> template.insert(MessageRow.class).using(row))
                .then());
    }

    private boolean isUniqueViolation(Throwable t) {
        return t instanceof DuplicateKeyException;
    }

    private Mono<Integer> nextSequenceNo(UUID sessionId) {
        return template.getDatabaseClient()
            .sql("SELECT COALESCE(MAX(sequence_no) + 1, 1) FROM messages WHERE session_id = :sid")
            .bind("sid", sessionId)
            .map(row -> row.get(0, Integer.class))
            .one();
    }

    private Mono<Void> audit(String eventType, String sessionId, String turnId, Map<String, Object> attributes) {
        return auditTrail.emit(AuditEvent.of(eventType, sessionId, turnId, attributes));
    }

    @Override
    public Mono<TranscriptPage> loadPage(String sessionId, int afterSequence, int limit) {
        UUID sessionUuid = UUID.fromString(sessionId);
        return template.select(MessageRow.class)
            .matching(Query.query(
                Criteria.where("session_id").is(sessionUuid)
                    .and("sequence_no").greaterThan(afterSequence)
            ).sort(Sort.by("sequence_no")).limit(limit + 1))
            .all()
            .collectList()
            .map(rows -> {
                boolean hasMore = rows.size() > limit;
                List<MessageRow> page = hasMore ? rows.subList(0, limit) : rows;

                List<SequencedMessage> sequenced = page.stream()
                    .map(row -> new SequencedMessage(row.sequenceNo(), row.toMessage()))
                    .toList();

                int nextCursor = sequenced.isEmpty() ? afterSequence
                    : sequenced.getLast().sequenceNo();
                return new TranscriptPage(sequenced, nextCursor, hasMore);
            });
    }
}
