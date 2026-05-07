package com.clawcode.agent.core.query;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

class QueryPropertiesTest {

    private static final Validator VALIDATOR =
        Validation.buildDefaultValidatorFactory().getValidator();

    @Nested
    @SpringBootTest
    class DefaultValues {

        @Autowired
        QueryProperties props;

        @Test
        void maxToolRoundsDefaultsToTen() {
            assertThat(props.maxToolRounds()).isEqualTo(10);
        }

        @Test
        void contextRecoveryDefaultsAreApplied() {
            assertThat(props.maxModelRequestChars()).isEqualTo(240000);
            assertThat(props.autoCompactEnabled()).isTrue();
            assertThat(props.compactPreserveRecentMessages()).isEqualTo(12);
            assertThat(props.maxOutputRecoveryAttempts()).isEqualTo(2);
        }
    }

    @Nested
    @SpringBootTest(properties = {
        "app.query.max-tool-rounds=3",
        "app.query.max-model-request-chars=50000",
        "app.query.auto-compact-enabled=false",
        "app.query.compact-preserve-recent-messages=5",
        "app.query.max-output-recovery-attempts=4"
    })
    class EnvOverride {

        @Autowired
        QueryProperties props;

        @Test
        void maxToolRoundsOverriddenByProperty() {
            assertThat(props.maxToolRounds()).isEqualTo(3);
        }

        @Test
        void contextRecoveryPropertiesOverriddenByProperty() {
            assertThat(props.maxModelRequestChars()).isEqualTo(50000);
            assertThat(props.autoCompactEnabled()).isFalse();
            assertThat(props.compactPreserveRecentMessages()).isEqualTo(5);
            assertThat(props.maxOutputRecoveryAttempts()).isEqualTo(4);
        }
    }

    @Test
    void zeroMaxToolRoundsFailsValidation() {
        Set<ConstraintViolation<QueryProperties>> violations =
            VALIDATOR.validate(props(0, 240000, true, 12, 2));
        assertThat(violations).isNotEmpty();
    }

    @Test
    void negativeMaxToolRoundsFailsValidation() {
        Set<ConstraintViolation<QueryProperties>> violations =
            VALIDATOR.validate(props(-1, 240000, true, 12, 2));
        assertThat(violations).isNotEmpty();
    }

    @Test
    void positiveMaxToolRoundsPassesValidation() {
        Set<ConstraintViolation<QueryProperties>> violations =
            VALIDATOR.validate(props(1, 240000, true, 12, 2));
        assertThat(violations).isEmpty();
    }

    @Test
    void belowMinMaxModelRequestCharsFailsValidation() {
        Set<ConstraintViolation<QueryProperties>> violations =
            VALIDATOR.validate(props(10, 9999, true, 12, 2));
        assertThat(violations).isNotEmpty();
    }

    @Test
    void minMaxModelRequestCharsPassesValidation() {
        Set<ConstraintViolation<QueryProperties>> violations =
            VALIDATOR.validate(props(10, 10000, true, 12, 2));
        assertThat(violations).isEmpty();
    }

    @Test
    void belowMinCompactPreserveRecentMessagesFailsValidation() {
        Set<ConstraintViolation<QueryProperties>> violations =
            VALIDATOR.validate(props(10, 240000, true, 1, 2));
        assertThat(violations).isNotEmpty();
    }

    @Test
    void minCompactPreserveRecentMessagesPassesValidation() {
        Set<ConstraintViolation<QueryProperties>> violations =
            VALIDATOR.validate(props(10, 240000, true, 2, 2));
        assertThat(violations).isEmpty();
    }

    @Test
    void negativeMaxOutputRecoveryAttemptsFailsValidation() {
        Set<ConstraintViolation<QueryProperties>> violations =
            VALIDATOR.validate(props(10, 240000, true, 12, -1));
        assertThat(violations).isNotEmpty();
    }

    @Test
    void zeroMaxOutputRecoveryAttemptsPassesValidation() {
        Set<ConstraintViolation<QueryProperties>> violations =
            VALIDATOR.validate(props(10, 240000, true, 12, 0));
        assertThat(violations).isEmpty();
    }

    private static QueryProperties props(
        int maxToolRounds,
        int maxModelRequestChars,
        boolean autoCompactEnabled,
        int compactPreserveRecentMessages,
        int maxOutputRecoveryAttempts
    ) {
        return new QueryProperties(
            maxToolRounds,
            maxModelRequestChars,
            autoCompactEnabled,
            compactPreserveRecentMessages,
            maxOutputRecoveryAttempts);
    }
}
