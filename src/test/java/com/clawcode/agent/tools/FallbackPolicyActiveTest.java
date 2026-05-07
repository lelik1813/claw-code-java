package com.clawcode.agent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.test.StepVerifier;

@SpringBootTest
class FallbackPolicyActiveTest {

    @Autowired
    ToolPermissionPolicy policy;

    @Test
    void fallbackAllowsAllWhenNoCustomPolicy() {
        StepVerifier.create(policy.decide(
            new ToolUseRequest("c1", "t", null),
            new ToolExecutionContext("t", "s", "m", null)))
            .expectNextMatches(d -> d instanceof ToolPermissionDecision.Allow)
            .verifyComplete();
    }
}
