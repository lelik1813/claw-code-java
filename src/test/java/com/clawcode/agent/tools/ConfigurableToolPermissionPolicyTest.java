package com.clawcode.agent.tools;

import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurableToolPermissionPolicyTest {

    private final ToolUseRequest fileRead = new ToolUseRequest("c1", "file_read", null);
    private final ToolUseRequest powershell = new ToolUseRequest("c2", "powershell", null);
    private final ToolExecutionContext ctx = new ToolExecutionContext("t", "s", "m", null);

    @Test
    void allowlist_permitsListedTool() {
        var policy = new ConfigurableToolPermissionPolicy(
            new ToolPermissionProperties(true, ToolPermissionProperties.Mode.ALLOWLIST,
                Set.of("file_read", "file_write")));

        StepVerifier.create(policy.decide(fileRead, ctx))
            .expectNextMatches(d -> d instanceof ToolPermissionDecision.Allow)
            .verifyComplete();
    }

    @Test
    void allowlist_deniesUnlistedTool() {
        var policy = new ConfigurableToolPermissionPolicy(
            new ToolPermissionProperties(true, ToolPermissionProperties.Mode.ALLOWLIST,
                Set.of("file_read")));

        StepVerifier.create(policy.decide(powershell, ctx))
            .expectNextMatches(d -> d instanceof ToolPermissionDecision.Deny deny
                && deny.reason().equals("current tool permission policy"))
            .verifyComplete();
    }

    @Test
    void allowlist_withEmptySet_deniesAll() {
        var policy = new ConfigurableToolPermissionPolicy(
            new ToolPermissionProperties(true, ToolPermissionProperties.Mode.ALLOWLIST,
                Set.of()));

        StepVerifier.create(policy.decide(fileRead, ctx))
            .expectNextMatches(d -> d instanceof ToolPermissionDecision.Deny)
            .verifyComplete();
    }

    @Test
    void denylist_permitsUnlistedTool() {
        var policy = new ConfigurableToolPermissionPolicy(
            new ToolPermissionProperties(true, ToolPermissionProperties.Mode.DENYLIST,
                Set.of("powershell")));

        StepVerifier.create(policy.decide(fileRead, ctx))
            .expectNextMatches(d -> d instanceof ToolPermissionDecision.Allow)
            .verifyComplete();
    }

    @Test
    void denylist_deniesListedTool() {
        var policy = new ConfigurableToolPermissionPolicy(
            new ToolPermissionProperties(true, ToolPermissionProperties.Mode.DENYLIST,
                Set.of("powershell")));

        StepVerifier.create(policy.decide(powershell, ctx))
            .expectNextMatches(d -> d instanceof ToolPermissionDecision.Deny deny
                && deny.reason().equals("current tool permission policy"))
            .verifyComplete();
    }

    @Test
    void denylist_withEmptySet_allowsAll() {
        var policy = new ConfigurableToolPermissionPolicy(
            new ToolPermissionProperties(true, ToolPermissionProperties.Mode.DENYLIST,
                Set.of()));

        StepVerifier.create(policy.decide(powershell, ctx))
            .expectNextMatches(d -> d instanceof ToolPermissionDecision.Allow)
            .verifyComplete();
    }

    @Test
    void defaultValuesDisablePolicy() {
        var props = new ToolPermissionProperties(false, null, null);
        assertThat(props.enabled()).isFalse();
        assertThat(props.mode()).isEqualTo(ToolPermissionProperties.Mode.ALLOWLIST);
        assertThat(props.allowedTools()).isEmpty();
    }
}
