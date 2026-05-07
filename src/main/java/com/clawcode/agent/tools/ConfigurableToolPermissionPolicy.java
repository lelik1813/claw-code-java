package com.clawcode.agent.tools;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration
@ConditionalOnProperty(prefix = "app.tools", name = "enabled", havingValue = "true")
public class ConfigurableToolPermissionPolicy implements ToolPermissionPolicy {

    private final ToolPermissionProperties properties;

    public ConfigurableToolPermissionPolicy(ToolPermissionProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<ToolPermissionDecision> decide(ToolUseRequest request, ToolExecutionContext context) {
        String toolName = request.toolName();
        if (ToolPermissionRules.isAllowed(properties, toolName)) {
            return Mono.just(new ToolPermissionDecision.Allow());
        }
        String policyReason = "current tool permission policy";
        return Mono.just(new ToolPermissionDecision.Deny(policyReason));
    }
}
