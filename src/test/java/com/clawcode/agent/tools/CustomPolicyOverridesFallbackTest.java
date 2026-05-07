package com.clawcode.agent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CustomPolicyOverridesFallbackTest {

    @TestConfiguration
    static class CustomPolicyConfig {
        @Bean
        ToolPermissionPolicy customPolicy() {
            return (req, ctx) -> Mono.just(
                new ToolPermissionDecision.Deny("blocked by custom policy"));
        }
    }

    @Autowired
    ToolPermissionPolicy policy;

    @Test
    void customPolicyIsInjectedNotFallback() {
        assertThat(policy.getClass().getSimpleName())
            .doesNotContain("AllowAll");

        StepVerifier.create(policy.decide(
            new ToolUseRequest("c1", "t", null),
            new ToolExecutionContext("t", "s", "m", null)))
            .expectNextMatches(d -> d instanceof ToolPermissionDecision.Deny deny
                && deny.reason().equals("blocked by custom policy"))
            .verifyComplete();
    }
}
