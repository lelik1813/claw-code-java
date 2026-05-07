package com.clawcode.agent.plugins;

import java.net.URI;
import java.util.List;
import java.util.Map;

public record PluginDescriptor(
    String id,
    String name,
    String version,
    URI location,
    List<PluginToolDescriptor> tools
) {

    public PluginDescriptor {
        tools = tools != null ? List.copyOf(tools) : List.of();
    }

    public PluginDescriptor(String id, String name, String version, URI location) {
        this(id, name, version, location, List.of());
    }
}

