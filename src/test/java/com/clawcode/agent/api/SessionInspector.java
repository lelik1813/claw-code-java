package com.clawcode.agent.api;

import com.clawcode.agent.model.ModelEvent;
import com.clawcode.agent.model.ModelRequest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import reactor.core.publisher.Flux;

public class SessionInspector {

    private final AtomicBoolean failNext = new AtomicBoolean(false);
    private final AtomicReference<ModelRequest> lastRequest = new AtomicReference<>();
    private final List<ModelRequest> allRequests = new CopyOnWriteArrayList<>();
    private volatile Function<ModelRequest, Flux<ModelEvent>> responseFn = req -> Flux.empty();

    public boolean shouldFail() {
        return failNext.getAndSet(false);
    }

    public void failNext() {
        failNext.set(true);
    }

    public void capture(ModelRequest request) {
        lastRequest.set(request);
        allRequests.add(request);
    }

    public ModelRequest lastRequest() {
        return lastRequest.get();
    }

    public List<ModelRequest> allRequests() {
        return allRequests;
    }

    public void setResponseFn(Function<ModelRequest, Flux<ModelEvent>> fn) {
        this.responseFn = fn;
    }

    public Function<ModelRequest, Flux<ModelEvent>> responseFn() {
        return responseFn;
    }

    public void reset() {
        allRequests.clear();
        lastRequest.set(null);
        failNext.set(false);
        responseFn = req -> Flux.empty();
    }
}
