package com.clawcode.agent.plugins;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.plugins")
public record PluginsProperties(
    @DefaultValue("false") boolean enabled,
    String marketplaceUrl,
    List<String> directories
) {

    public PluginsProperties(boolean enabled, String marketplaceUrl, List<String> directories) {
        this.enabled = enabled;
        this.marketplaceUrl = marketplaceUrl;
        this.directories = directories != null ? directories : List.of();
    }
}
