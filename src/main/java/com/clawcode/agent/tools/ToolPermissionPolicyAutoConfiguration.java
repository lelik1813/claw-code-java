package com.clawcode.agent.tools;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration
@ConditionalOnMissingBean(ToolPermissionPolicy.class)
public class ToolPermissionPolicyAutoConfiguration {

    @Bean
    ToolPermissionPolicy allowAllToolPermissionPolicy() {
        return new AllowAllToolPermissionPolicy();
    }

    static class AllowAllToolPermissionPolicy implements ToolPermissionPolicy {

        @Override
        public Mono<ToolPermissionDecision> decide(ToolUseRequest request, ToolExecutionContext context) {
            return Mono.just(new ToolPermissionDecision.Allow());
        }
    }
}
