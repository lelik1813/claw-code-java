package com.clawcode.agent.tools.web;

import reactor.core.publisher.Flux;

public interface WebSearchClient {

    Flux<SearchResultItem> search(String query, int limit);
}
