package com.clawcode.agent.tools.web;

import com.clawcode.agent.tools.Tool;
import com.clawcode.agent.tools.ToolDefinition;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

public class WebFetchTool implements Tool {

    private final WebClientFactory clientFactory;
    private final WebUrlGuard urlGuard;
    private final int timeoutMs;
    private final int maxResponseBytes;
    private final int maxTextChars;

    public WebFetchTool(WebToolsProperties props) {
        this(new WebUrlGuard(props), props);
    }

    public WebFetchTool(WebUrlGuard urlGuard, WebToolsProperties props) {
        this.urlGuard = urlGuard;
        this.timeoutMs = props.timeoutMs();
        this.maxResponseBytes = props.maxResponseBytes();
        this.maxTextChars = props.maxTextChars();
        this.clientFactory = new WebClientFactory(props);
    }

    private static final ToolDefinition DEFINITION = new ToolDefinition(
        "web_fetch",
        "Fetch a web page by URL and extract its text content. "
            + "Only HTTP and HTTPS schemes are allowed. "
            + "Returns a JSON object with 'url' and 'text' fields. "
            + "HTML is stripped to plain text; output is truncated at 50K chars.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "url", Map.of("type", "string",
                    "description", "Absolute URL to fetch (http or https).")
            ),
            "required", List.of("url"),
            "additionalProperties", false
        )
    );

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Mono<Object> execute(Object input, Object context) {
        String rawUrl = extractUrl(input);
        if (rawUrl == null || rawUrl.isBlank()) {
            return Mono.error(new IllegalArgumentException("url must not be empty"));
        }

        URI uri = urlGuard.validateAndNormalize(rawUrl);

        return clientFactory.create()
            .get()
            .uri(uri)
            .retrieve()
            .bodyToFlux(String.class)
            .reduce(new StringBuilder(), StringBuilder::append)
            .map(sb -> extractText(sb.toString()))
            .timeout(Duration.ofMillis(timeoutMs))
            .map(text -> (Object) Map.of("url", uri.toString(), "text", text));
    }

    String extractText(String html) {
        String cleaned = Jsoup.clean(html, Safelist.none());
        String normalized = cleaned.replaceAll("\\s+", " ").trim();
        if (normalized.length() > maxTextChars) {
            normalized = normalized.substring(0, maxTextChars) + "... [truncated]";
        }
        return normalized;
    }

    private String extractUrl(Object input) {
        if (input instanceof Map<?, ?> map) {
            Object url = map.get("url");
            return url != null ? url.toString() : null;
        }
        return input != null ? input.toString() : null;
    }

    static class WebClientFactory {
        private final WebToolsProperties props;

        WebClientFactory(WebToolsProperties props) {
            this.props = props;
        }

        org.springframework.web.reactive.function.client.WebClient create() {
            return org.springframework.web.reactive.function.client.WebClient.builder()
                .codecs(configurer -> configurer
                    .defaultCodecs()
                    .maxInMemorySize(props.maxResponseBytes()))
                .build();
        }
    }
}
