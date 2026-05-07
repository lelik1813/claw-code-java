package com.clawcode.agent.model;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@ConditionalOnExpression("'${anthropic.auth-token:}'.isEmpty()")
public class NoopModelClient implements ModelClient {

    @Override
    public Flux<ModelEvent> stream(ModelRequest request) {
        return Flux.just(
            new ModelStreamStartedEvent(request.model()),
            new ModelTextDeltaEvent("noop"),
            new ModelCompletedEvent()
        );
    }
}
