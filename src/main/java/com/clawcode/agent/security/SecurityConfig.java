package com.clawcode.agent.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final byte[] UNAUTHORIZED_BODY;
    private static final byte[] FORBIDDEN_BODY;

    static {
        var mapper = new ObjectMapper();
        try {
            UNAUTHORIZED_BODY = mapper.writeValueAsBytes(Map.of("error", "Unauthorized"));
            FORBIDDEN_BODY = mapper.writeValueAsBytes(Map.of("error", "Forbidden"));
        } catch (JsonProcessingException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.security.api-key", name = "enabled", havingValue = "true")
    SecurityWebFilterChain securedChain(ServerHttpSecurity http, ApiSecurityProperties props) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                .pathMatchers("/api/**").authenticated()
                .anyExchange().permitAll()
            )
            .exceptionHandling(handler -> handler
                .authenticationEntryPoint((exchange, ex) ->
                    writeJson(exchange, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_BODY))
                .accessDeniedHandler((exchange, denied) ->
                    writeJson(exchange, HttpStatus.FORBIDDEN, FORBIDDEN_BODY))
            )
            .addFilterAt(new ApiKeyAuthWebFilter(props.apiKey()), SecurityWebFiltersOrder.AUTHENTICATION)
            .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.security.api-key", name = "enabled", havingValue = "false", matchIfMissing = true)
    SecurityWebFilterChain openChain(ServerHttpSecurity http) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
            .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.security.rate-limit", name = "enabled", havingValue = "true")
    RateLimitWebFilter rateLimitWebFilter(ApiSecurityProperties props) {
        var rl = props.rateLimit();
        return new RateLimitWebFilter(props.apiKey().header(), rl.requests(), rl.windowSeconds());
    }

    private static Mono<Void> writeJson(ServerWebExchange exchange, HttpStatus status, byte[] body) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var buffer = new DefaultDataBufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
