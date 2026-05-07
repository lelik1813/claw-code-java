package com.clawcode.agent.model;

import reactor.core.publisher.Flux;

public interface ModelClient {
    Flux<ModelEvent> stream(ModelRequest request);
}
