package com.clawcode.agent.core.query;

import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.UserMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBudgetGuardTest {

    private final ContextBudgetGuard guard =
        new ContextBudgetGuard(new ModelRequestSizeEstimator());

    @Test
    void withinBudgetReturnsFitResult() {
        ContextBudgetCheck check = guard.check(
            "system",
            List.of(user("hello")),
            List.of(),
            20);

        assertThat(check.withinBudget()).isTrue();
        assertThat(check.estimatedChars()).isEqualTo(11);
        assertThat(check.maxChars()).isEqualTo(20);
        assertThat(check.failureMessage()).isNull();
    }

    @Test
    void exactBoundaryIsWithinBudget() {
        ContextBudgetCheck check = guard.check(
            "abc",
            List.of(user("def")),
            List.of(),
            6);

        assertThat(check.withinBudget()).isTrue();
        assertThat(check.estimatedChars()).isEqualTo(6);
        assertThat(check.failureMessage()).isNull();
    }

    @Test
    void overBudgetReturnsFailure() {
        ContextBudgetCheck check = guard.check(
            "system",
            List.of(user("too large")),
            List.of(),
            10);

        assertThat(check.withinBudget()).isFalse();
        assertThat(check.estimatedChars()).isEqualTo(15);
        assertThat(check.maxChars()).isEqualTo(10);
        assertThat(check.failureMessage()).isEqualTo(ContextBudgetGuard.FAILURE_MESSAGE);
    }

    @Test
    void failureMessageIsDeterministicAscii() {
        assertThat(ContextBudgetGuard.FAILURE_MESSAGE)
            .isEqualTo("Context is too large for this model request. Run /compact or start a new session, then retry.");
        assertThat(ContextBudgetGuard.FAILURE_MESSAGE.chars().allMatch(ch -> ch <= 127)).isTrue();
    }

    private static Message user(String content) {
        return new UserMessage(UUID.randomUUID(), Instant.EPOCH, content);
    }
}
