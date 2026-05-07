package com.clawcode.agent.plugins;

import com.clawcode.agent.tools.Tool;
import java.util.List;
import java.util.Optional;

public interface PluginToolFactory {

    Optional<Tool> tryCreate(PluginToolDescriptor descriptor);

    default List<Tool> createAll(PluginDescriptor plugin) {
        return plugin.tools().stream()
            .map(this::tryCreate)
            .flatMap(Optional::stream)
            .toList();
    }
}
