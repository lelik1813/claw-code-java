package com.clawcode.agent.core.query;

import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TurnCommandTest {

    @Test
    void backwardCompatConstructorDefaultsPersistFromIndexToMessagesSize() {
        List<Message> messages = List.of(
            new UserMessage(UUID.randomUUID(), Instant.now(), "a"),
            new UserMessage(UUID.randomUUID(), Instant.now(), "b")
        );
        var cmd = new TurnCommand("t1", "s1", messages, "m", "p", List.of());

        assertThat(cmd.persistFromIndex()).isEqualTo(2);
    }

    @Test
    void backwardCompatConstructorWithEmptyMessagesDefaultsToZero() {
        var cmd = new TurnCommand("t1", "s1", List.of(), "m", "p", List.of());

        assertThat(cmd.persistFromIndex()).isEqualTo(0);
    }

    @Test
    void explicitPersistFromIndexTakesPrecedence() {
        List<Message> messages = List.of(
            new UserMessage(UUID.randomUUID(), Instant.now(), "x")
        );
        var cmd = new TurnCommand("t1", "s1", messages, "m", "p", List.of(), 0);

        assertThat(cmd.persistFromIndex()).isEqualTo(0);
    }
}
