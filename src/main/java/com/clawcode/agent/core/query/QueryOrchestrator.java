package com.clawcode.agent.core.query;

import reactor.core.publisher.Flux;

public interface QueryOrchestrator {
    Flux<QueryEvent> runTurn(TurnCommand command);
}
