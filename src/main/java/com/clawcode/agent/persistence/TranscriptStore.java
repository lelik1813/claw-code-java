package com.clawcode.agent.persistence;

import com.clawcode.agent.shared.message.Message;
import java.util.List;
import reactor.core.publisher.Mono;

public interface TranscriptStore {
    Mono<List<Message>> load(String sessionId, String turnId);

    Mono<Void> append(String sessionId, String turnId, List<Message> messages);

    Mono<TranscriptPage> loadPage(String sessionId, int afterSequence, int limit);

    record TranscriptPage(List<SequencedMessage> messages, int nextCursor, boolean hasMore) {}

    record SequencedMessage(int sequenceNo, Message message) {}
}
