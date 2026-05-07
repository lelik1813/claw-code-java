package com.clawcode.agent.security;

import java.util.List;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

public class ApiKeyAuthWebFilter implements WebFilter {

    private static final String ROLE_API = "ROLE_API";

    private final String headerName;
    private final String validKey;

    ApiKeyAuthWebFilter(ApiSecurityProperties.ApiKey apiKey) {
        this.headerName = apiKey.header();
        this.validKey = apiKey.key();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isSecuredPath(exchange.getRequest())) {
            return chain.filter(exchange);
        }

        String presented = exchange.getRequest().getHeaders().getFirst(headerName);

        if (presented != null && presented.equals(validKey)) {
            var auth = new UsernamePasswordAuthenticationToken(
                "api-key", presented,
                List.of(new SimpleGrantedAuthority(ROLE_API)));
            return chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
        }

        return chain.filter(exchange);
    }

    private boolean isSecuredPath(ServerHttpRequest request) {
        String path = request.getPath().value();
        return path.startsWith("/api/");
    }
}
