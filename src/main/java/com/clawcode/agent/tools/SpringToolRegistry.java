package com.clawcode.agent.tools;

import com.clawcode.agent.plugins.PluginDescriptor;
import com.clawcode.agent.plugins.PluginRegistry;
import com.clawcode.agent.plugins.PluginToolFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static java.util.stream.Collectors.toList;

@Component
public class SpringToolRegistry implements ToolRegistry {

    private static final Duration PLUGIN_LOAD_TIMEOUT = Duration.ofSeconds(10);

    private final Map<String, Tool> tools;
    private final Set<String> names;

    public SpringToolRegistry() {
        this(Collections.emptyList());
    }

    public SpringToolRegistry(Iterable<Tool> tools) {
        List<Tool> list = new ArrayList<>();
        tools.forEach(list::add);
        this.tools = buildMap(list);
        this.names = Collections.unmodifiableSet(this.tools.keySet());
    }

    @Autowired
    public SpringToolRegistry(
        List<Tool> springTools,
        @Autowired(required = false) PluginToolFactory pluginToolFactory,
        @Autowired(required = false) PluginRegistry pluginRegistry
    ) {
        List<Tool> all = new ArrayList<>(springTools);
        if (pluginToolFactory != null && pluginRegistry != null) {
            List<PluginDescriptor> plugins = pluginRegistry.list()
                .collectList().block(PLUGIN_LOAD_TIMEOUT);
            if (plugins != null) {
                for (PluginDescriptor plugin : plugins) {
                    all.addAll(pluginToolFactory.createAll(plugin));
                }
            }
        }
        this.tools = buildMap(all);
        this.names = Collections.unmodifiableSet(this.tools.keySet());
    }

    @Override
    public Optional<Tool> findByName(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public Set<String> listNames() {
        return names;
    }

    @Override
    public List<ToolDefinition> definitions() {
        return tools.values().stream()
            .map(Tool::definition)
            .collect(toList());
    }

    private static Map<String, Tool> buildMap(List<Tool> tools) {
        Map<String, Tool> map = new LinkedHashMap<>();
        for (Tool tool : tools) {
            Tool existing = map.put(tool.name(), tool);
            if (existing != null) {
                throw new IllegalStateException(
                    "Duplicate tool name: " + tool.name());
            }
        }
        return Collections.unmodifiableMap(map);
    }
}
