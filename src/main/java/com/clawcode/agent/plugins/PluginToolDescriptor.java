package com.clawcode.agent.plugins;

import java.util.Map;

public record PluginToolDescriptor(
    String name,
    String type,
    Map<String, Object> config
) {

    public PluginToolDescriptor {
        config = config != null ? Map.copyOf(config) : Map.of();
    }
}
