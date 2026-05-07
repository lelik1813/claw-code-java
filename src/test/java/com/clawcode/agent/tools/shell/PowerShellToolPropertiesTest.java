package com.clawcode.agent.tools.shell;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

class PowerShellToolPropertiesTest {

    private static final Validator VALIDATOR =
        Validation.buildDefaultValidatorFactory().getValidator();

    @Nested
    @SpringBootTest
    class DefaultValues {

        @Autowired
        PowerShellToolProperties props;

        @Test
        void timeoutSecondsDefaultsToThirty() {
            assertThat(props.timeoutSeconds()).isEqualTo(30);
        }
    }

    @Nested
    @SpringBootTest(properties = "app.tools.powershell.timeout-seconds=5")
    class EnvOverride {

        @Autowired
        PowerShellToolProperties props;

        @Test
        void timeoutSecondsOverriddenByProperty() {
            assertThat(props.timeoutSeconds()).isEqualTo(5);
        }
    }

    @Test
    void zeroTimeoutSecondsFailsValidation() {
        Set<ConstraintViolation<PowerShellToolProperties>> violations =
            VALIDATOR.validate(new PowerShellToolProperties(0));
        assertThat(violations).isNotEmpty();
    }

    @Test
    void positiveTimeoutSecondsPassesValidation() {
        Set<ConstraintViolation<PowerShellToolProperties>> violations =
            VALIDATOR.validate(new PowerShellToolProperties(1));
        assertThat(violations).isEmpty();
    }
}
