package com.clawcode.agent.tools.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class WebSearchToolTest {

    @Test
    void successfulSearchReturnsResults() {
        WebSearchClient client = (query, limit) -> Flux.just(
            new SearchResultItem("Title 1", "https://example.com/1", "Snippet 1", "test"),
            new SearchResultItem("Title 2", "https://example.com/2", "Snippet 2", "test")
        );
        WebSearchTool tool = new WebSearchTool(client);

        StepVerifier.create(tool.execute(Map.of("query", "test"), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> items = (List<Map<String, String>>) result;
                assertThat(items).hasSize(2);
                assertThat(items.get(0)).containsEntry("title", "Title 1");
                assertThat(items.get(0)).containsEntry("url", "https://example.com/1");
                assertThat(items.get(0)).containsEntry("snippet", "Snippet 1");
            })
            .verifyComplete();
    }

    @Test
    void emptyQueryReturnsError() {
        WebSearchTool tool = new WebSearchTool((q, l) -> Flux.empty());

        StepVerifier.create(tool.execute(Map.of("query", ""), null))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().equals("query must not be empty"))
            .verify();
    }

    @Test
    void missingQueryReturnsError() {
        WebSearchTool tool = new WebSearchTool((q, l) -> Flux.empty());

        StepVerifier.create(tool.execute(Map.of(), null))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().equals("query must not be empty"))
            .verify();
    }

    @Test
    void nullInputReturnsError() {
        WebSearchTool tool = new WebSearchTool((q, l) -> Flux.empty());

        StepVerifier.create(tool.execute(null, null))
            .expectErrorMatches(e -> e instanceof IllegalArgumentException
                && e.getMessage().equals("query must not be empty"))
            .verify();
    }

    @Test
    void providerErrorPropagates() {
        WebSearchClient client = (query, limit) ->
            Flux.error(new RuntimeException("API rate limit exceeded"));
        WebSearchTool tool = new WebSearchTool(client);

        StepVerifier.create(tool.execute(Map.of("query", "test"), null))
            .expectErrorMatches(e -> e instanceof RuntimeException
                && e.getMessage().equals("API rate limit exceeded"))
            .verify();
    }

    @Test
    void limitParameterPassedToClient() {
        WebSearchClient client = (query, limit) -> {
            assertThat(limit).isEqualTo(2);
            return Flux.just(
                new SearchResultItem("R1", "https://a.com/1", "S1", "t"),
                new SearchResultItem("R2", "https://a.com/2", "S2", "t")
            );
        };
        WebSearchTool tool = new WebSearchTool(client);

        StepVerifier.create(tool.execute(Map.of("query", "test", "limit", 2), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> items = (List<Map<String, String>>) result;
                assertThat(items).hasSize(2);
            })
            .verifyComplete();
    }

    @Test
    void defaultLimitIsTen() {
        WebSearchTool tool = new WebSearchTool((q, l) -> {
            assertThat(l).isEqualTo(10);
            return Flux.empty();
        });

        StepVerifier.create(tool.execute(Map.of("query", "test"), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> items = (List<Map<String, String>>) result;
                assertThat(items).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void emptyResultsReturnsEmptyList() {
        WebSearchTool tool = new WebSearchTool((q, l) -> Flux.empty());

        StepVerifier.create(tool.execute(Map.of("query", "obscure query"), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> items = (List<Map<String, String>>) result;
                assertThat(items).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void stringInputUsedAsQuery() {
        WebSearchTool tool = new WebSearchTool((q, l) -> {
            assertThat(q).isEqualTo("plain text query");
            return Flux.just(new SearchResultItem("T", "https://x.com", "S", "src"));
        });

        StepVerifier.create(tool.execute("plain text query", null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> items = (List<Map<String, String>>) result;
                assertThat(items).hasSize(1);
            })
            .verifyComplete();
    }

    @Test
    void nameIsWebSearch() {
        assertThat(new WebSearchTool((q, l) -> Flux.empty()).name())
            .isEqualTo("web_search");
    }
}
