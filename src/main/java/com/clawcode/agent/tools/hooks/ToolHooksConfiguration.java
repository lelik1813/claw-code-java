package com.clawcode.agent.tools.hooks;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolHooksConfiguration {

    @Bean
    ToolHookPipeline toolHookPipeline(List<ToolExecutionHook> hooks) {
        return new ToolHookPipeline(hooks);
    }
}
