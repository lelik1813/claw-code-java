package com.clawcode.agent.plugins;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.plugins", name = "enabled", havingValue = "true")
class PluginsAutoConfiguration {

    @Bean
    PluginToolFactory pluginToolFactory() {
        return new DefaultPluginToolFactory();
    }
}
