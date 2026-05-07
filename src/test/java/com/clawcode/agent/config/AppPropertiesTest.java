package com.clawcode.agent.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesTest {

    private static final Validator VALIDATOR =
        Validation.buildDefaultValidatorFactory().getValidator();

    @Nested
    @SpringBootTest
    class DefaultValues {

        @Autowired
        AppProperties props;

        @Test
        void maxToolConcurrencyDefaultsToFour() {
            assertThat(props.maxToolConcurrency()).isEqualTo(4);
        }

        @Test
        void toolResultBudgetDefaultsAreApplied() {
            assertThat(props.maxToolResultChars()).isEqualTo(12000);
            assertThat(props.toolResultExcerptChars()).isEqualTo(4000);
            assertThat(props.toolSummaryMinCalls()).isEqualTo(4);
        }
    }

    @Nested
    @SpringBootTest(properties = {
        "app.tools.max-tool-concurrency=8",
        "app.tools.max-tool-result-chars=16000",
        "app.tools.tool-result-excerpt-chars=3000",
        "app.tools.tool-summary-min-calls=6"
    })
    class EnvOverride {

        @Autowired
        AppProperties props;

        @Test
        void maxToolConcurrencyOverriddenByProperty() {
            assertThat(props.maxToolConcurrency()).isEqualTo(8);
        }

        @Test
        void toolResultBudgetOverriddenByProperty() {
            assertThat(props.maxToolResultChars()).isEqualTo(16000);
            assertThat(props.toolResultExcerptChars()).isEqualTo(3000);
            assertThat(props.toolSummaryMinCalls()).isEqualTo(6);
        }
    }

    @Test
    void zeroMaxToolConcurrencyFailsValidation() {
        Set<ConstraintViolation<AppProperties>> violations =
            VALIDATOR.validate(props(0, 12000, 4000, 4));
        assertThat(violations).isNotEmpty();
    }

    @Test
    void negativeMaxToolConcurrencyFailsValidation() {
        Set<ConstraintViolation<AppProperties>> violations =
            VALIDATOR.validate(props(-1, 12000, 4000, 4));
        assertThat(violations).isNotEmpty();
    }

    @Test
    void positiveMaxToolConcurrencyPassesValidation() {
        Set<ConstraintViolation<AppProperties>> violations =
            VALIDATOR.validate(props(1, 12000, 4000, 4));
        assertThat(violations).isEmpty();
    }

    @Test
    void belowMinMaxToolResultCharsFailsValidation() {
        Set<ConstraintViolation<AppProperties>> violations =
            VALIDATOR.validate(props(4, 999, 4000, 4));
        assertThat(violations).isNotEmpty();
    }

    @Test
    void minMaxToolResultCharsPassesValidation() {
        Set<ConstraintViolation<AppProperties>> violations =
            VALIDATOR.validate(props(4, 1000, 4000, 4));
        assertThat(violations).isEmpty();
    }

    @Test
    void belowMinToolResultExcerptCharsFailsValidation() {
        Set<ConstraintViolation<AppProperties>> violations =
            VALIDATOR.validate(props(4, 12000, 199, 4));
        assertThat(violations).isNotEmpty();
    }

    @Test
    void minToolResultExcerptCharsPassesValidation() {
        Set<ConstraintViolation<AppProperties>> violations =
            VALIDATOR.validate(props(4, 12000, 200, 4));
        assertThat(violations).isEmpty();
    }

    @Test
    void belowMinToolSummaryMinCallsFailsValidation() {
        Set<ConstraintViolation<AppProperties>> violations =
            VALIDATOR.validate(props(4, 12000, 4000, 1));
        assertThat(violations).isNotEmpty();
    }

    @Test
    void minToolSummaryMinCallsPassesValidation() {
        Set<ConstraintViolation<AppProperties>> violations =
            VALIDATOR.validate(props(4, 12000, 4000, 2));
        assertThat(violations).isEmpty();
    }

    private static AppProperties props(
        int maxToolConcurrency,
        int maxToolResultChars,
        int toolResultExcerptChars,
        int toolSummaryMinCalls
    ) {
        return new AppProperties(
            maxToolConcurrency,
            maxToolResultChars,
            toolResultExcerptChars,
            toolSummaryMinCalls);
    }
}
