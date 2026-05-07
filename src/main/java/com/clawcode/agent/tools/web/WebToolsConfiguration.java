package com.clawcode.agent.tools.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.web-tools", name = "enabled", havingValue = "true")
public class WebToolsConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.web-tools", name = "search-enabled", havingValue = "true", matchIfMissing = true)
    WebSearchClient httpWebSearchClient(WebToolsProperties props) {
        return new HttpWebSearchClient(props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.web-tools", name = "search-enabled", havingValue = "true", matchIfMissing = true)
    WebSearchTool webSearchTool(WebSearchClient client) {
        return new WebSearchTool(client);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.web-tools", name = "fetch-enabled", havingValue = "true", matchIfMissing = true)
    WebFetchTool webFetchTool(WebToolsProperties props) {
        return new WebFetchTool(props);
    }
}
