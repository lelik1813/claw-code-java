package com.clawcode.agent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ConfigurableToolPermissionPolicyContextTest {

    @Autowired
    ToolPermissionPolicy policy;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.tools.enabled", () -> "true");
        registry.add("app.tools.mode", () -> "ALLOWLIST");
        registry.add("app.tools.allowed-tools", () -> "file_read");
    }

    @Test
    void configurablePolicyIsActivatedFromProperties() {
        assertThat(policy).isInstanceOf(ConfigurableToolPermissionPolicy.class);
    }

    @Test
    void allowedToolPasses() {
        var request = new ToolUseRequest("c1", "file_read", null);
        var ctx = new ToolExecutionContext("t", "s", "m", null);

        StepVerifier.create(policy.decide(request, ctx))
            .expectNextMatches(d -> d instanceof ToolPermissionDecision.Allow)
            .verifyComplete();
    }

    @Test
    void disallowedToolIsDenied() {
        var request = new ToolUseRequest("c2", "powershell", null);
        var ctx = new ToolExecutionContext("t", "s", "m", null);

        StepVerifier.create(policy.decide(request, ctx))
            .expectNextMatches(d -> d instanceof ToolPermissionDecision.Deny deny
                && deny.reason().equals("current tool permission policy"))
            .verifyComplete();
    }
}
