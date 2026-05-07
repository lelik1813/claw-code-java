package com.clawcode.agent.persistence;

import com.clawcode.agent.forensics.AuditTrail;
import com.clawcode.agent.persistence.postgres.SessionRow;
import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.UserMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "PERSISTENCE_BACKEND=r2dbc",
    "app.persistence.backend=r2dbc",
    "spring.autoconfigure.exclude="
})
class PostgresTranscriptStoreIT {

    private static final AuditTrail noopAudit = event -> reactor.core.publisher.Mono.empty();
    private static final com.clawcode.agent.forensics.ObservabilityMetrics noopMetrics =
        new com.clawcode.agent.forensics.ObservabilityMetrics(
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    TranscriptStore transcriptStore;

    @Autowired
    R2dbcEntityTemplate template;

    @Autowired
    TransactionalOperator txOp;

    @BeforeEach
    void cleanDb() {
        template.getDatabaseClient()
            .sql("TRUNCATE sessions CASCADE")
            .then()
            .block(Duration.ofSeconds(5));

        Long sessions = template.getDatabaseClient()
            .sql("SELECT count(*) FROM sessions")
            .map(row -> row.get(0, Long.class))
            .one()
            .block(Duration.ofSeconds(5));
        Long messages = template.getDatabaseClient()
            .sql("SELECT count(*) FROM messages")
            .map(row -> row.get(0, Long.class))
            .one()
            .block(Duration.ofSeconds(5));
        assertThat(sessions).isZero();
        assertThat(messages).isZero();
    }

    private UUID insertSession() {
        UUID id = UUID.randomUUID();
        template.insert(SessionRow.class)
            .using(new SessionRow(id, Instant.now()))
            .block(Duration.ofSeconds(5));
        return id;
    }

    @Test
    void appendAndLoadSingleUserMessage() {
        UUID sessionId = insertSession();
        String sid = sessionId.toString();

        UserMessage msg = new UserMessage(UUID.randomUUID(), Instant.now(), "hello");
        transcriptStore.append(sid, null, List.of(msg)).block(Duration.ofSeconds(5));

        List<Message> loaded = transcriptStore.load(sid, null).block(Duration.ofSeconds(5));

        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0)).isInstanceOf(UserMessage.class);
        assertThat(loaded.get(0).uuid()).isEqualTo(msg.uuid());
        assertThat(((UserMessage) loaded.get(0)).content()).isEqualTo("hello");
    }

    @Test
    void appendAndLoadUserAndAssistantMessages() {
        UUID sessionId = insertSession();
        String sid = sessionId.toString();

        UserMessage user = new UserMessage(UUID.randomUUID(), Instant.now(), "hi");
        AssistantMessage assistant = new AssistantMessage(UUID.randomUUID(), Instant.now(), "hello!");
        transcriptStore.append(sid, null, List.of(user, assistant)).block(Duration.ofSeconds(5));

        List<Message> loaded = transcriptStore.load(sid, null).block(Duration.ofSeconds(5));

        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) loaded.get(0)).content()).isEqualTo("hi");
        assertThat(loaded.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) loaded.get(1)).textContent()).isEqualTo("hello!");
    }

    @Test
    void orderingPreservedAcrossMultipleAppends() {
        UUID sessionId = insertSession();
        String sid = sessionId.toString();

        UserMessage user1 = new UserMessage(UUID.randomUUID(), Instant.now(), "q1");
        transcriptStore.append(sid, null, List.of(user1)).block(Duration.ofSeconds(5));

        AssistantMessage assistant = new AssistantMessage(UUID.randomUUID(), Instant.now(), "a1");
        transcriptStore.append(sid, null, List.of(assistant)).block(Duration.ofSeconds(5));

        UserMessage user2 = new UserMessage(UUID.randomUUID(), Instant.now(), "q2");
        transcriptStore.append(sid, null, List.of(user2)).block(Duration.ofSeconds(5));

        List<Message> loaded = transcriptStore.load(sid, null).block(Duration.ofSeconds(5));

        assertThat(loaded).hasSize(3);
        assertThat(((UserMessage) loaded.get(0)).content()).isEqualTo("q1");
        assertThat(((AssistantMessage) loaded.get(1)).textContent()).isEqualTo("a1");
        assertThat(((UserMessage) loaded.get(2)).content()).isEqualTo("q2");
    }

    @Test
    void dataSurvivesBeanRecreation() {
        UUID sessionId = insertSession();
        String sid = sessionId.toString();

        UserMessage msg = new UserMessage(UUID.randomUUID(), Instant.now(), "persistent");
        transcriptStore.append(sid, null, List.of(msg)).block(Duration.ofSeconds(5));

        PostgresTranscriptStore freshStore = new PostgresTranscriptStore(template, txOp, noopAudit, noopMetrics);
        List<Message> loaded = freshStore.load(sid, null).block(Duration.ofSeconds(5));

        assertThat(loaded).hasSize(1);
        assertThat(((UserMessage) loaded.get(0)).content()).isEqualTo("persistent");
        assertThat(loaded.get(0).uuid()).isEqualTo(msg.uuid());
    }

    @Test
    void concurrentAppendsAllPersistWithoutLoss() {
        UUID sessionId = insertSession();
        String sid = sessionId.toString();

        UserMessage batch1 = new UserMessage(UUID.randomUUID(), Instant.now(), "batch-1");
        UserMessage batch2 = new UserMessage(UUID.randomUUID(), Instant.now(), "batch-2");
        UserMessage batch3 = new UserMessage(UUID.randomUUID(), Instant.now(), "batch-3");

        Mono.when(
            transcriptStore.append(sid, null, List.of(batch1)).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()),
            transcriptStore.append(sid, null, List.of(batch2)).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()),
            transcriptStore.append(sid, null, List.of(batch3)).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        ).block(Duration.ofSeconds(10));

        List<Message> loaded = transcriptStore.load(sid, null).block(Duration.ofSeconds(5));

        assertThat(loaded).hasSize(3);
        assertThat(loaded.stream().map(m -> ((UserMessage) m).content()).collect(Collectors.toSet()))
            .containsExactlyInAnyOrder("batch-1", "batch-2", "batch-3");

        Long total = template.getDatabaseClient()
            .sql("SELECT count(*) FROM messages WHERE session_id = :sid")
            .bind("sid", sessionId)
            .map(row -> row.get(0, Long.class))
            .one()
            .block(Duration.ofSeconds(5));
        Long distinct = template.getDatabaseClient()
            .sql("SELECT count(DISTINCT sequence_no) FROM messages WHERE session_id = :sid")
            .bind("sid", sessionId)
            .map(row -> row.get(0, Long.class))
            .one()
            .block(Duration.ofSeconds(5));
        assertThat(distinct).isEqualTo(total);
    }

    @Test
    void duplicateSequenceIsRejectedByDatabase() {
        UUID sessionId = insertSession();
        template.getDatabaseClient()
            .sql("INSERT INTO messages (id, session_id, role, content, sequence_no, created_at) VALUES (:id, :sid, 'user', 'first', 1, now())")
            .bind("id", UUID.randomUUID()).bind("sid", sessionId)
            .fetch().rowsUpdated()
            .block(Duration.ofSeconds(5));

        assertThatThrownBy(() ->
            template.getDatabaseClient()
                .sql("INSERT INTO messages (id, session_id, role, content, sequence_no, created_at) VALUES (:id, :sid, 'user', 'dup', 1, now())")
                .bind("id", UUID.randomUUID()).bind("sid", sessionId)
                .fetch().rowsUpdated()
                .block(Duration.ofSeconds(5))
        ).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Nested
    class CursorBasedPagination {

        @Test
        void loadPageReturnsFirstPage() {
            UUID sessionId = insertSession();
            String sid = sessionId.toString();

            for (int i = 1; i <= 5; i++) {
                transcriptStore.append(sid, null, List.of(
                    new UserMessage(UUID.randomUUID(), Instant.now(), "msg-" + i)
                )).block(Duration.ofSeconds(5));
            }

            TranscriptStore.TranscriptPage page = transcriptStore.loadPage(sid, 0, 3)
                .block(Duration.ofSeconds(5));

            assertThat(page.messages()).hasSize(3);
            assertThat(page.hasMore()).isTrue();
            assertThat(page.nextCursor()).isEqualTo(3);
            assertThat(page.messages().get(0).sequenceNo()).isEqualTo(1);
            assertThat(page.messages().get(2).sequenceNo()).isEqualTo(3);
        }

        @Test
        void loadPageReturnsNextPage() {
            UUID sessionId = insertSession();
            String sid = sessionId.toString();

            for (int i = 1; i <= 5; i++) {
                transcriptStore.append(sid, null, List.of(
                    new UserMessage(UUID.randomUUID(), Instant.now(), "msg-" + i)
                )).block(Duration.ofSeconds(5));
            }

            TranscriptStore.TranscriptPage page1 = transcriptStore.loadPage(sid, 0, 3)
                .block(Duration.ofSeconds(5));

            TranscriptStore.TranscriptPage page2 = transcriptStore.loadPage(sid, page1.nextCursor(), 3)
                .block(Duration.ofSeconds(5));

            assertThat(page2.messages()).hasSize(2);
            assertThat(page2.hasMore()).isFalse();
            assertThat(page2.messages().get(0).sequenceNo()).isEqualTo(4);
            assertThat(page2.messages().get(1).sequenceNo()).isEqualTo(5);
        }

        @Test
        void loadPageAfterSequenceSkipsCorrectly() {
            UUID sessionId = insertSession();
            String sid = sessionId.toString();

            for (int i = 1; i <= 5; i++) {
                transcriptStore.append(sid, null, List.of(
                    new UserMessage(UUID.randomUUID(), Instant.now(), "msg-" + i)
                )).block(Duration.ofSeconds(5));
            }

            TranscriptStore.TranscriptPage page = transcriptStore.loadPage(sid, 2, 10)
                .block(Duration.ofSeconds(5));

            assertThat(page.messages()).hasSize(3);
            assertThat(page.messages().get(0).sequenceNo()).isEqualTo(3);
            assertThat(page.hasMore()).isFalse();
        }

        @Test
        void loadPageOnEmptySessionReturnsEmpty() {
            UUID sessionId = insertSession();
            String sid = sessionId.toString();

            TranscriptStore.TranscriptPage page = transcriptStore.loadPage(sid, 0, 10)
                .block(Duration.ofSeconds(5));

            assertThat(page.messages()).isEmpty();
            assertThat(page.hasMore()).isFalse();
            assertThat(page.nextCursor()).isEqualTo(0);
        }
    }
}
