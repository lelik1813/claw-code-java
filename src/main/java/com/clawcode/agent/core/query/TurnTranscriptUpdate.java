package com.clawcode.agent.core.query;

import com.clawcode.agent.shared.message.Message;
import java.util.List;

/**
 * Boundary payload carrying only the new non-user messages produced during a turn
 * (assistant tool_use messages, tool_result messages, final assistant text).
 * The already-persisted user message is intentionally excluded.
 * Used by SessionService to persist turn output without CLI text buffer involvement.
 */
public record TurnTranscriptUpdate(List<Message> messagesToPersist) {

    public TurnTranscriptUpdate {
        messagesToPersist = List.copyOf(messagesToPersist);
    }
}
