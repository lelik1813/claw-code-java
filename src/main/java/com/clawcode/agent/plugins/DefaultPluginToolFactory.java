package com.clawcode.agent.plugins;

import com.clawcode.agent.tools.Tool;
import com.clawcode.agent.tools.ToolDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

public class DefaultPluginToolFactory implements PluginToolFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultPluginToolFactory.class);
    private static final long DEFAULT_MAX_RESPONSE_BYTES = 1_048_576;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final Map<String, Function<PluginToolDescriptor, Tool>> creators;

    public DefaultPluginToolFactory() {
        this.creators = new LinkedHashMap<>();
        creators.put("http", this::createHttpTool);
        creators.put("https", this::createHttpTool);
    }

    @Override
    public Optional<Tool> tryCreate(PluginToolDescriptor descriptor) {
        if (descriptor == null) {
            return Optional.empty();
        }
        Function<PluginToolDescriptor, Tool> creator = creators.get(descriptor.type());
        if (creator == null) {
            return Optional.empty();
        }
        return Optional.of(creator.apply(descriptor));
    }

    @Override
    public List<Tool> createAll(PluginDescriptor plugin) {
        List<Tool> result = new ArrayList<>();
        for (PluginToolDescriptor desc : plugin.tools()) {
            try {
                tryCreate(desc).ifPresent(result::add);
            } catch (Exception e) {
                log.warn("Plugin '{}' tool '{}' failed to create: {}",
                    plugin.id(), desc.name(), e.getMessage());
            }
        }
        return result;
    }

    void registerType(String type, Function<PluginToolDescriptor, Tool> creator) {
        creators.put(type, creator);
    }

    private Tool createHttpTool(PluginToolDescriptor descriptor) {
        String url = stringConfig(descriptor, "url");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(
                "HTTP tool '" + descriptor.name() + "' requires non-blank 'url' config");
        }
        String method = stringConfig(descriptor, "method");
        long timeout = longConfig(descriptor, "timeout", DEFAULT_TIMEOUT.toMillis());
        long maxBytes = longConfig(descriptor, "maxResponseBytes", DEFAULT_MAX_RESPONSE_BYTES);

        WebClient client = WebClient.builder()
            .baseUrl(url)
            .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize((int) maxBytes))
            .build();

        return new HttpPluginTool(descriptor.name(), method, client, Duration.ofMillis(timeout));
    }

    private static String stringConfig(PluginToolDescriptor descriptor, String key) {
        Object val = descriptor.config().get(key);
        return val != null ? val.toString() : null;
    }

    private static long longConfig(PluginToolDescriptor descriptor, String key, long defaultValue) {
        Object val = descriptor.config().get(key);
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    static class HttpPluginTool implements Tool {

        private static final String DEFAULT_METHOD = "GET";

        private final ToolDefinition toolDefinition;
        private final String method;
        private final WebClient client;
        private final Duration timeout;

        HttpPluginTool(String toolName, String method, WebClient client, Duration timeout) {
            this.toolDefinition = new ToolDefinition(
                toolName,
                "HTTP plugin tool (" + (method != null ? method.toUpperCase() : DEFAULT_METHOD) + ").",
                Map.of("type", "object", "properties", Map.of())
            );
            this.method = method != null && !method.isBlank() ? method.toUpperCase() : DEFAULT_METHOD;
            this.client = client;
            this.timeout = timeout;
        }

        @Override
        public String name() {
            return toolDefinition.name();
        }

        @Override
        public ToolDefinition definition() {
            return toolDefinition;
        }

        @Override
        public Mono<Object> execute(Object input, Object context) {
            return client.method(org.springframework.http.HttpMethod.valueOf(method))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .map(body -> (Object) body)
                .onErrorResume(e -> Mono.just(
                    "[plugin tool error: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "]"));
        }
    }
}
