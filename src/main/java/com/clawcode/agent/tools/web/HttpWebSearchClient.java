package com.clawcode.agent.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

class HttpWebSearchClient implements WebSearchClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final String source;

    HttpWebSearchClient(WebToolsProperties props) {
        this.source = props.searchBaseUrl();
        this.webClient = WebClient.builder()
            .baseUrl(props.searchBaseUrl())
            .defaultHeader("Accept", "application/json")
            .defaultHeader("X-Subscription-Token", props.searchApiKey())
            .codecs(configurer -> configurer
                .defaultCodecs()
                .maxInMemorySize(props.maxResponseBytes()))
            .build();
    }

    @Override
    public Flux<SearchResultItem> search(String query, int limit) {
        return webClient.get()
            .uri(uriBuilder -> uriBuilder
                .queryParam("q", query)
                .queryParam("count", limit)
                .build())
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofMillis(30_000))
            .mapNotNull(this::parseJson)
            .flatMapMany(json -> Flux.fromIterable(extractResults(json)))
            .take(limit);
    }

    private List<SearchResultItem> extractResults(JsonNode json) {
        List<SearchResultItem> results = new ArrayList<>();
        JsonNode items = json.has("web") ? json.get("web").get("results") : json.get("results");
        if (items == null || !items.isArray()) {
            return results;
        }
        for (JsonNode item : items) {
            String title = textField(item, "title");
            String url = textField(item, "url", "link");
            String snippet = textField(item, "description", "snippet");
            if (url != null) {
                results.add(new SearchResultItem(title, url, snippet, source));
            }
        }
        return results;
    }

    private JsonNode parseJson(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String textField(JsonNode node, String... names) {
        for (String name : names) {
            if (node.has(name) && !node.get(name).isNull()) {
                String val = node.get(name).asText();
                if (!val.isBlank()) return val;
            }
        }
        return null;
    }
}
