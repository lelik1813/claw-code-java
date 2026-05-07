package com.clawcode.agent.core.query;

import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.ToolResultMessage;
import com.clawcode.agent.shared.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TurnTranscriptUpdateTest {

    private static final UUID ID = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    @Test
    void defensiveCopyPreventsExternalMutation() {
        var mutable = new ArrayList<Message>();
        var update = new TurnTranscriptUpdate(mutable);

        mutable.add(new UserMessage(ID, NOW, "mutated"));

        assertThat(update.messagesToPersist()).isEmpty();
    }

    @Test
    void defensiveCopyPreventsInternalLeakage() {
        var inner = new ArrayList<Message>();
        inner.add(new AssistantMessage(ID, NOW, "hello"));
        inner.add(new ToolResultMessage(ID, NOW, "tc-1", "grep", "result", false));

        var update = new TurnTranscriptUpdate(inner);
        var leaked = update.messagesToPersist();

        assertThat(leaked).isUnmodifiable();
    }
}
