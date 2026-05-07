package com.clawcode.agent.tools.hooks;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnMissingBean(ToolExecutionHook.class)
public class ToolHooksAutoConfiguration {

    @Bean
    ToolExecutionHook noopToolExecutionHook() {
        return new NoopToolExecutionHook();
    }
}
