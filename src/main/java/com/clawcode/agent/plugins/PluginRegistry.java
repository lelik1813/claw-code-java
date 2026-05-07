package com.clawcode.agent.plugins;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PluginRegistry {

    Flux<PluginDescriptor> list();

    Mono<PluginDescriptor> resolve(String pluginId);
}
