package com.clawcode.agent.persistence;

import com.clawcode.agent.forensics.AuditTrail;
import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.UserMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTranscriptStorePageTest {

    private static final AuditTrail NOOP_AUDIT = event -> Mono.empty();

    @Test
    void loadPageReturnsFirstPage() {
        var store = new InMemoryTranscriptStore(NOOP_AUDIT);
        String sid = "session-1";

        for (int i = 1; i <= 5; i++) {
            store.append(sid, null, List.of(
                new UserMessage(UUID.randomUUID(), Instant.now(), "msg-" + i)
            )).block(Duration.ofSeconds(5));
        }

        TranscriptStore.TranscriptPage page = store.loadPage(sid, 0, 3)
            .block(Duration.ofSeconds(5));

        assertThat(page.messages()).hasSize(3);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo(3);
        assertThat(page.messages().get(0).sequenceNo()).isEqualTo(1);
        assertThat(page.messages().get(2).sequenceNo()).isEqualTo(3);
    }

    @Test
    void loadPageReturnsSecondPage() {
        var store = new InMemoryTranscriptStore(NOOP_AUDIT);
        String sid = "session-1";

        for (int i = 1; i <= 5; i++) {
            store.append(sid, null, List.of(
                new UserMessage(UUID.randomUUID(), Instant.now(), "msg-" + i)
            )).block(Duration.ofSeconds(5));
        }

        TranscriptStore.TranscriptPage page1 = store.loadPage(sid, 0, 3)
            .block(Duration.ofSeconds(5));

        TranscriptStore.TranscriptPage page2 = store.loadPage(sid, page1.nextCursor(), 3)
            .block(Duration.ofSeconds(5));

        assertThat(page2.messages()).hasSize(2);
        assertThat(page2.hasMore()).isFalse();
        assertThat(page2.messages().get(0).sequenceNo()).isEqualTo(4);
        assertThat(page2.messages().get(1).sequenceNo()).isEqualTo(5);
    }

    @Test
    void loadPageOnEmptySessionReturnsEmpty() {
        var store = new InMemoryTranscriptStore(NOOP_AUDIT);

        TranscriptStore.TranscriptPage page = store.loadPage("empty-session", 0, 10)
            .block(Duration.ofSeconds(5));

        assertThat(page.messages()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isEqualTo(0);
    }

    @Test
    void loadPagePreservesMessageType() {
        var store = new InMemoryTranscriptStore(NOOP_AUDIT);
        String sid = "session-1";

        UserMessage user = new UserMessage(UUID.randomUUID(), Instant.now(), "hello");
        AssistantMessage assistant = new AssistantMessage(UUID.randomUUID(), Instant.now(), "world");
        store.append(sid, null, List.of(user, assistant)).block(Duration.ofSeconds(5));

        TranscriptStore.TranscriptPage page = store.loadPage(sid, 0, 10)
            .block(Duration.ofSeconds(5));

        assertThat(page.messages()).hasSize(2);
        assertThat(page.messages().get(0).message()).isInstanceOf(UserMessage.class);
        assertThat(page.messages().get(1).message()).isInstanceOf(AssistantMessage.class);
    }

    @Test
    void loadPageWithLimitLargerThanTotalReturnsAll() {
        var store = new InMemoryTranscriptStore(NOOP_AUDIT);
        String sid = "session-1";

        store.append(sid, null, List.of(
            new UserMessage(UUID.randomUUID(), Instant.now(), "only-msg")
        )).block(Duration.ofSeconds(5));

        TranscriptStore.TranscriptPage page = store.loadPage(sid, 0, 100)
            .block(Duration.ofSeconds(5));

        assertThat(page.messages()).hasSize(1);
        assertThat(page.hasMore()).isFalse();
    }
}
