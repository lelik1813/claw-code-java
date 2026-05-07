package com.clawcode.agent.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

public class RateLimitWebFilter implements WebFilter {

    private static final byte[] TOO_MANY_REQUESTS_BODY;

    static {
        try {
            TOO_MANY_REQUESTS_BODY = new ObjectMapper()
                .writeValueAsBytes(Map.of("error", "Too Many Requests"));
        } catch (JsonProcessingException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final String apiKeyHeader;
    private final int maxRequests;
    private final long windowMs;
    private final ConcurrentHashMap<String, Window> buckets = new ConcurrentHashMap<>();

    RateLimitWebFilter(String apiKeyHeader, int maxRequests, int windowSeconds) {
        this.apiKeyHeader = apiKeyHeader;
        this.maxRequests = maxRequests;
        this.windowMs = windowSeconds * 1000L;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isApiPath(exchange.getRequest())) {
            return chain.filter(exchange);
        }

        String key = resolveKey(exchange);
        Window window = buckets.compute(key, (k, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null || now - existing.startMs >= windowMs) {
                return new Window(now, 1);
            }
            existing.count++;
            return existing;
        });

        if (window.count > maxRequests) {
            return writeJson(exchange, HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_REQUESTS_BODY);
        }

        return chain.filter(exchange);
    }

    private String resolveKey(ServerWebExchange exchange) {
        String apiKey = exchange.getRequest().getHeaders().getFirst(apiKeyHeader);
        if (apiKey != null && !apiKey.isBlank()) {
            return "key:" + apiKey;
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return "ip:" + remote.getAddress().getHostAddress();
        }
        return "unknown";
    }

    private boolean isApiPath(ServerHttpRequest request) {
        return request.getPath().value().startsWith("/api/");
    }

    private static Mono<Void> writeJson(ServerWebExchange exchange, HttpStatus status, byte[] body) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(new DefaultDataBufferFactory().wrap(body)));
    }

    static final class Window {
        final long startMs;
        int count;

        Window(long startMs, int count) {
            this.startMs = startMs;
            this.count = count;
        }
    }
}
