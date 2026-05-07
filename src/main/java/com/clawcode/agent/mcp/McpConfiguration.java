package com.clawcode.agent.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
public class McpConfiguration {

    @Bean
    public McpClient mcpClientRouter(McpProperties properties) {
        return new McpClientRouter(properties);
    }

    @Bean
    public McpService mcpService(McpProperties properties, McpClient mcpClientRouter) {
        return new McpService(properties, mcpClientRouter);
    }
}
