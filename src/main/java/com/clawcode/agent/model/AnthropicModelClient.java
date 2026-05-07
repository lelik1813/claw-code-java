package com.clawcode.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawcode.agent.shared.message.AssistantContentBlock;
import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.AssistantTextBlock;
import com.clawcode.agent.shared.message.AssistantThinkingBlock;
import com.clawcode.agent.shared.message.AssistantToolUseBlock;
import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.ToolResultMessage;
import com.clawcode.agent.shared.message.UserMessage;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

@Component
@ConditionalOnExpression("!'${anthropic.auth-token:}'.isEmpty()")
public class AnthropicModelClient implements ModelClient {

    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final WebClient webClient;
    private final AnthropicProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnthropicModelClient(AnthropicProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.builder()
            .baseUrl(properties.baseUrl())
            .build();
    }

    @Override
    public Flux<ModelEvent> stream(ModelRequest request) {
        String model = resolveModel(request.model());
        List<ApiMessage> messages = toApiMessages(request.messages());
        List<ApiTool> tools = request.tools() != null && !request.tools().isEmpty()
            ? request.tools().stream().map(this::toApiTool).toList()
            : null;

        Object body = new ApiRequest(
            model, DEFAULT_MAX_TOKENS, true,
            request.systemPrompt(), messages, tools
        );

        ToolUseBuffer buffer = new ToolUseBuffer();

        return webClient.post()
            .uri("/v1/messages")
            .header("x-api-key", properties.authToken())
            .header("anthropic-version", ANTHROPIC_VERSION)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
            .filter(sse -> sse.data() != null)
            .flatMap(sse -> parseSseEvent(sse.data(), buffer))
            .timeout(Duration.ofMillis(properties.effectiveTimeoutMs()))
            .retryWhen(Retry.backoff(2, Duration.ofMillis(250))
                .filter(this::isTransientRequestFailure)
                .onRetryExhaustedThrow((spec, signal) ->
                    new RuntimeException(
                        "Anthropic API request failed after retries before a response was received: "
                            + signal.failure().getMessage(),
                        signal.failure())))
            .onErrorMap(WebClientResponseException.class, e ->
                new RuntimeException(
                    "Anthropic API error: " + e.getStatusCode()
                        + " — " + e.getResponseBodyAsString(), e)
            )
            .onErrorMap(WebClientRequestException.class, e ->
                new RuntimeException(
                    "Anthropic API request failed before a response was received: "
                        + e.getMessage(), e)
            );
    }

    private boolean isTransientRequestFailure(Throwable error) {
        return error instanceof WebClientRequestException;
    }

    private String resolveModel(String requested) {
        if (requested == null || requested.isBlank() || requested.equals("default")) {
            return properties.defaultModel();
        }
        return requested;
    }

    private List<ApiMessage> toApiMessages(List<Message> messages) {
        java.util.ArrayList<ApiMessage> apiMessages = new java.util.ArrayList<>();
        java.util.ArrayList<Map<String, Object>> pendingToolResults = new java.util.ArrayList<>();
        boolean skippedBlankAssistant = false;
        for (Message message : messages) {
            if (message instanceof ToolResultMessage toolResult) {
                if (!skippedBlankAssistant || !pendingToolResults.isEmpty() || lastAssistantHasToolUse(apiMessages)) {
                    pendingToolResults.add(toToolResultBlock(toolResult));
                }
                continue;
            }
            flushToolResults(apiMessages, pendingToolResults);
            ApiMessage apiMessage = toApiMessage(message);
            if (apiMessage != null) {
                apiMessages.add(apiMessage);
                skippedBlankAssistant = false;
            } else if (message instanceof AssistantMessage) {
                skippedBlankAssistant = true;
            } else {
                skippedBlankAssistant = false;
            }
        }
        flushToolResults(apiMessages, pendingToolResults);
        return List.copyOf(apiMessages);
    }

    private boolean lastAssistantHasToolUse(List<ApiMessage> apiMessages) {
        if (apiMessages.isEmpty()) {
            return false;
        }
        ApiMessage last = apiMessages.getLast();
        if (!"assistant".equals(last.role()) || !(last.content() instanceof List<?> blocks)) {
            return false;
        }
        return blocks.stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .anyMatch(block -> "tool_use".equals(block.get("type")));
    }

    private void flushToolResults(
        List<ApiMessage> apiMessages,
        List<Map<String, Object>> pendingToolResults
    ) {
        if (pendingToolResults.isEmpty()) {
            return;
        }
        apiMessages.add(new ApiMessage("user", List.copyOf(pendingToolResults)));
        pendingToolResults.clear();
    }

    private ApiMessage toApiMessage(Message msg) {
        if (msg instanceof UserMessage u) {
            return new ApiMessage("user", u.content());
        }
        if (msg instanceof AssistantMessage a) {
            boolean hasToolUse = a.content().stream()
                .anyMatch(AssistantToolUseBlock.class::isInstance);
            boolean hasThinking = a.content().stream()
                .anyMatch(AssistantThinkingBlock.class::isInstance);
            if (hasToolUse || hasThinking) {
                List<Map<String, Object>> blocks = a.content().stream()
                    .map(this::toContentBlock)
                    .toList();
                return new ApiMessage("assistant", blocks);
            }
            String text = a.textContent();
            return text.isBlank() ? null : new ApiMessage("assistant", text);
        }
        if (msg instanceof ToolResultMessage t) {
            return new ApiMessage("user", List.of(toToolResultBlock(t)));
        }
        throw new IllegalArgumentException("Unknown message type: " + msg.getClass());
    }

    private Map<String, Object> toToolResultBlock(ToolResultMessage t) {
        Map<String, Object> block = new HashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", t.toolCallId());
        block.put("content", t.content() != null ? t.content() : "");
        if (t.isError()) {
            block.put("is_error", true);
        }
        return block;
    }

    private ApiTool toApiTool(ModelToolDefinition tool) {
        return new ApiTool(tool.name(), tool.description(), tool.inputSchema());
    }

    private Map<String, Object> toContentBlock(AssistantContentBlock block) {
        if (block instanceof AssistantTextBlock t) {
            Map<String, Object> m = new HashMap<>();
            m.put("type", "text");
            m.put("text", t.text());
            return m;
        }
        if (block instanceof AssistantToolUseBlock tu) {
            Map<String, Object> m = new HashMap<>();
            m.put("type", "tool_use");
            m.put("id", tu.id());
            m.put("name", tu.name());
            m.put("input", tu.input());
            return m;
        }
        if (block instanceof AssistantThinkingBlock thinking) {
            Map<String, Object> m = new HashMap<>();
            m.put("type", "thinking");
            m.put("thinking", thinking.thinking());
            if (thinking.signature() != null && !thinking.signature().isBlank()) {
                m.put("signature", thinking.signature());
            }
            return m;
        }
        throw new IllegalArgumentException("Unknown block type: " + block.getClass());
    }

    private Flux<ModelEvent> parseSseEvent(String data, ToolUseBuffer buffer) {
        if ("[DONE]".equals(data.strip())) {
            return Flux.empty();
        }

        try {
            JsonNode node = objectMapper.readTree(data);
            String type = node.path("type").asText();

            return switch (type) {
                case "message_start" -> Flux.just(new ModelStreamStartedEvent(
                    node.path("message").path("model").asText("unknown")
                ));
                case "content_block_start" -> buffer.onContentBlockStart(node);
                case "content_block_delta" -> buffer.onContentBlockDelta(node);
                case "content_block_stop" -> buffer.onContentBlockStop(node);
                case "message_delta" -> {
                    String stopReason = node.path("delta").path("stop_reason").asText(null);
                    JsonNode usage = node.path("usage");
                    Long outputTokens = usage.has("output_tokens")
                        ? usage.get("output_tokens").asLong() : null;
                    Flux<ModelEvent> events = Flux.empty();
                    if (stopReason != null && !stopReason.isEmpty()) {
                        events = events.concatWith(Flux.just(new ModelStopReasonEvent(stopReason)));
                    }
                    if (outputTokens != null) {
                        events = events.concatWith(Flux.just(new ModelUsageEvent(null, outputTokens)));
                    }
                    yield events;
                }
                case "message_stop" -> Flux.just(new ModelCompletedEvent());
                case "error" -> Flux.just(new ModelErrorEvent(
                    node.path("error").path("message").asText("unknown"),
                    node.path("error").path("type").asText(null)
                ));
                default -> Flux.empty();
            };
        } catch (Exception e) {
            return Flux.error(
                new RuntimeException("Failed to parse SSE event: " + data, e));
        }
    }

    static final class ToolUseBuffer {
        private final Map<Integer, PendingToolUse> pending = new HashMap<>();
        private final Map<Integer, PendingThinkingBlock> thinking = new HashMap<>();

        Flux<ModelEvent> onContentBlockStart(JsonNode node) {
            JsonNode block = node.path("content_block");
            int index = node.path("index").asInt();
            String blockType = block.path("type").asText();
            if ("tool_use".equals(blockType)) {
                pending.put(index, new PendingToolUse(
                    block.path("id").asText(),
                    block.path("name").asText(),
                    new StringBuilder()
                ));
            } else if ("thinking".equals(blockType)) {
                PendingThinkingBlock pendingThinking = new PendingThinkingBlock();
                if (block.hasNonNull("thinking")) {
                    pendingThinking.thinking.append(block.path("thinking").asText());
                }
                if (block.hasNonNull("signature")) {
                    pendingThinking.signature = block.path("signature").asText();
                }
                thinking.put(index, pendingThinking);
            }
            return Flux.empty();
        }

        Flux<ModelEvent> onContentBlockDelta(JsonNode node) {
            JsonNode delta = node.path("delta");
            if (delta.has("text")) {
                String text = delta.path("text").asText();
                return text.isEmpty() ? Flux.empty() : Flux.just(new ModelTextDeltaEvent(text));
            }
            if ("input_json_delta".equals(delta.path("type").asText())) {
                int index = node.path("index").asInt();
                PendingToolUse pendingTool = pending.get(index);
                if (pendingTool != null) {
                    pendingTool.jsonBuilder.append(delta.path("partial_json").asText());
                }
                return Flux.empty();
            }
            String deltaType = delta.path("type").asText("");
            if ("thinking_delta".equals(deltaType)) {
                PendingThinkingBlock pendingThinking = thinking.get(node.path("index").asInt());
                if (pendingThinking != null) {
                    pendingThinking.thinking.append(delta.path("thinking").asText());
                }
                return Flux.empty();
            }
            if ("signature_delta".equals(deltaType)) {
                PendingThinkingBlock pendingThinking = thinking.get(node.path("index").asInt());
                if (pendingThinking != null) {
                    pendingThinking.signature = delta.path("signature").asText(null);
                }
                return Flux.empty();
            }
            return Flux.empty();
        }

        Flux<ModelEvent> onContentBlockStop(JsonNode node) {
            int index = node.path("index").asInt();
            PendingThinkingBlock pendingThinking = thinking.remove(index);
            if (pendingThinking != null) {
                return Flux.just(new ModelThinkingBlockEvent(
                    new AssistantThinkingBlock(
                        pendingThinking.thinking.toString(),
                        pendingThinking.signature)));
            }
            PendingToolUse pendingTool = pending.remove(index);
            if (pendingTool == null) {
                return Flux.empty();
            }
            Object input = parseInputJson(pendingTool.jsonBuilder.toString());
            return Flux.just(new ModelToolUseEvent(
                pendingTool.toolUseId, pendingTool.toolName, input, index));
        }

        private Object parseInputJson(String json) {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            try {
                return new ObjectMapper().readValue(json, Object.class);
            } catch (Exception e) {
                return json;
            }
        }
    }

    private static final class PendingToolUse {
        final String toolUseId;
        final String toolName;
        final StringBuilder jsonBuilder;

        PendingToolUse(String toolUseId, String toolName, StringBuilder jsonBuilder) {
            this.toolUseId = toolUseId;
            this.toolName = toolName;
            this.jsonBuilder = jsonBuilder;
        }
    }

    private static final class PendingThinkingBlock {
        final StringBuilder thinking = new StringBuilder();
        String signature;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ApiRequest(
        String model, int max_tokens, boolean stream,
        String system, List<ApiMessage> messages, List<ApiTool> tools
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ApiMessage(String role, Object content) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ApiTool(String name, String description, Map<String, Object> input_schema) {}
}
