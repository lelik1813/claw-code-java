package com.clawcode.agent.core.query;

import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.UserMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TurnStateTest {

    private final List<Message> initialHistory = List.of(
        new UserMessage(UUID.randomUUID(), Instant.now(), "hello")
    );

    @Test
    void recordsUsage() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);

        state.recordUsage(100L, 50L);

        assertThat(state.latestInputTokens()).isEqualTo(100L);
        assertThat(state.latestOutputTokens()).isEqualTo(50L);
    }

    @Test
    void overridesUsageOnSubsequentCalls() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);

        state.recordUsage(100L, 50L);
        state.recordUsage(200L, 75L);

        assertThat(state.latestInputTokens()).isEqualTo(200L);
        assertThat(state.latestOutputTokens()).isEqualTo(75L);
    }

    @Test
    void recordsRoundCount() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);

        state.recordRound();
        state.recordRound();
        state.recordRound();

        assertThat(state.roundCount()).isEqualTo(3);
    }

    @Test
    void recordsPermissionDenials() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);

        state.recordPermissionDenial();

        assertThat(state.permissionDenialCount()).isEqualTo(1);
    }

    @Test
    void recordsMaxOutputRecoveryAttempts() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);

        state.recordMaxOutputRecoveryAttempt();
        state.recordMaxOutputRecoveryAttempt();

        assertThat(state.maxOutputRecoveryAttempts()).isEqualTo(2);
    }

    @Test
    void recordsStopHookRetries() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);

        state.recordStopHookRetry();
        state.recordStopHookRetry();

        assertThat(state.stopHookRetries()).isEqualTo(2);
    }

    @Test
    void recoveredPartialTextAccumulatesAndConsumeClearsBuffer() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);

        state.appendRecoveredPartialText("first ");
        state.appendRecoveredPartialText(null);
        state.appendRecoveredPartialText("second");

        assertThat(state.consumeRecoveredPartialText()).isEqualTo("first second");
        assertThat(state.consumeRecoveredPartialText()).isEmpty();
    }

    @Test
    void recoveredPartialTextDoesNotEnterTranscriptDelta() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);

        state.appendRecoveredPartialText("partial answer");

        assertThat(state.history()).isEqualTo(initialHistory);
        assertThat(state.modelHistory()).isEqualTo(initialHistory);
        assertThat(state.takeUnpersistedDelta()).isEmpty();
    }

    @Test
    void recordsStopReason() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);
        assertThat(state.stopReason()).isNull();

        state.stopReason("end_turn");

        assertThat(state.stopReason()).isEqualTo("end_turn");
    }

    @Test
    void finishDurationMsIsPositiveAfterConstruction() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);

        long duration = state.finishDurationMs();

        assertThat(duration).isGreaterThanOrEqualTo(0);
    }

    @Test
    void finishDurationMsIncreasesOverTime() throws Exception {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);

        Thread.sleep(10);
        long duration = state.finishDurationMs();

        assertThat(duration).isGreaterThanOrEqualTo(5);
    }

    @Test
    void startedAtIsSetOnConstruction() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);

        assertThat(state.startedAt()).isGreaterThan(0);
    }

    @Test
    void historyIsDefensiveCopy() {
        var mutable = new java.util.ArrayList<>(initialHistory);
        var state = new TurnState("t1", "s1", "model", "prompt", mutable);

        mutable.add(new UserMessage(UUID.randomUUID(), Instant.now(), "extra"));
        List<Message> copy = state.history();

        assertThat(copy).hasSize(1);
    }

    @Test
    void historyReturnsUnmodifiableList() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);
        var history = state.history();

        assertThat(history).isUnmodifiable();
    }

    @Test
    void initialModelHistoryEqualsPersistedHistory() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);

        assertThat(state.modelHistory()).isEqualTo(state.history());
    }

    @Test
    void addMessageModifiesHistoryAndModelHistory() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);

        Message message = new UserMessage(UUID.randomUUID(), Instant.now(), "world");
        state.addMessage(message);

        assertThat(state.history()).hasSize(2);
        assertThat(state.modelHistory()).hasSize(2);
        assertThat(state.history().get(1)).isSameAs(message);
        assertThat(state.modelHistory().get(1)).isSameAs(message);
    }

    @Test
    void replaceModelHistoryDoesNotChangePersistedHistory() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);
        Message compacted = new UserMessage(UUID.randomUUID(), Instant.now(), "compacted");

        state.replaceModelHistory(List.of(compacted));

        assertThat(state.history()).isEqualTo(initialHistory);
        assertThat(state.modelHistory()).containsExactly(compacted);
        assertThat(state.turnDelta()).isEmpty();
    }

    @Test
    void addModelOnlyMessageDoesNotEnterTranscriptDelta() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);
        Message modelOnly = new UserMessage(UUID.randomUUID(), Instant.now(), "internal meta");

        state.addModelOnlyMessage(modelOnly);

        assertThat(state.history()).isEqualTo(initialHistory);
        assertThat(state.modelHistory()).containsExactly(initialHistory.get(0), modelOnly);
        assertThat(state.takeUnpersistedDelta()).isEmpty();
    }

    @Test
    void turnDeltaIsEmptyAfterConstruction() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);

        assertThat(state.turnDelta()).isEmpty();
    }

    @Test
    void turnDeltaContainsOnlyAddedMessages() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);

        state.addMessage(new UserMessage(UUID.randomUUID(), Instant.now(), "first"));
        state.addMessage(new UserMessage(UUID.randomUUID(), Instant.now(), "second"));

        assertThat(state.turnDelta()).hasSize(2);
        assertThat(((UserMessage) state.turnDelta().get(0)).content()).isEqualTo("first");
        assertThat(((UserMessage) state.turnDelta().get(1)).content()).isEqualTo("second");
    }

    @Test
    void explicitPersistFromIndexSlicesCorrectly() {
        List<Message> history = List.of(
            new UserMessage(UUID.randomUUID(), Instant.now(), "pre-existing"),
            new UserMessage(UUID.randomUUID(), Instant.now(), "pre-existing2")
        );
        var state = new TurnState("t1", "s1", "model", "prompt", history, 1);

        assertThat(state.turnDelta()).hasSize(1);
        assertThat(((UserMessage) state.turnDelta().get(0)).content()).isEqualTo("pre-existing2");

        state.addMessage(new UserMessage(UUID.randomUUID(), Instant.now(), "new-msg"));
        assertThat(state.turnDelta()).hasSize(2);
        assertThat(((UserMessage) state.turnDelta().get(1)).content()).isEqualTo("new-msg");
    }

    @Test
    void takeUnpersistedDeltaReturnsOnlyNewMessagesPerCall() {
        var state = new TurnState("t1", "s1", "model", "prompt", initialHistory);

        state.addMessage(new UserMessage(UUID.randomUUID(), Instant.now(), "first"));
        List<Message> firstTake = state.takeUnpersistedDelta();
        assertThat(firstTake).hasSize(1);
        assertThat(((UserMessage) firstTake.get(0)).content()).isEqualTo("first");

        state.addMessage(new UserMessage(UUID.randomUUID(), Instant.now(), "second"));
        List<Message> secondTake = state.takeUnpersistedDelta();
        assertThat(secondTake).hasSize(1);
        assertThat(((UserMessage) secondTake.get(0)).content()).isEqualTo("second");

        assertThat(state.takeUnpersistedDelta()).isEmpty();
    }

    @Test
    void takeUnpersistedDeltaStartsFromExplicitPersistIndex() {
        List<Message> history = List.of(
            new UserMessage(UUID.randomUUID(), Instant.now(), "pre-existing"),
            new UserMessage(UUID.randomUUID(), Instant.now(), "pre-existing2")
        );
        var state = new TurnState("t1", "s1", "model", "prompt", history, 1);

        List<Message> firstTake = state.takeUnpersistedDelta();
        assertThat(firstTake).hasSize(1);
        assertThat(((UserMessage) firstTake.get(0)).content()).isEqualTo("pre-existing2");

        state.addMessage(new UserMessage(UUID.randomUUID(), Instant.now(), "new-msg"));
        List<Message> secondTake = state.takeUnpersistedDelta();
        assertThat(secondTake).hasSize(1);
        assertThat(((UserMessage) secondTake.get(0)).content()).isEqualTo("new-msg");
    }
}
