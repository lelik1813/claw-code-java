package com.clawcode.agent.core.session;

import com.clawcode.agent.model.ModelClient;
import com.clawcode.agent.model.ModelCompletedEvent;
import com.clawcode.agent.model.ModelStreamStartedEvent;
import com.clawcode.agent.model.ModelTextDeltaEvent;
import com.clawcode.agent.model.ModelToolUseEvent;
import com.clawcode.agent.persistence.TranscriptStore;
import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.AssistantTextBlock;
import com.clawcode.agent.shared.message.AssistantToolUseBlock;
import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.ToolResultMessage;
import com.clawcode.agent.shared.message.UserMessage;
import com.clawcode.agent.tools.*;
import com.clawcode.agent.tools.hooks.TestToolExecutionHook;
import com.clawcode.agent.tools.hooks.ToolExecutionHook;
import com.clawcode.agent.tools.hooks.ToolPostHookResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.clawcode.agent.core.query.QueryEvent;
import com.clawcode.agent.core.query.QueryTranscriptUpdateEvent;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class SessionServiceTranscriptTest {

    @Nested
    @SpringBootTest
    class SilentModelTests {

        @Autowired
        SessionService sessionService;

        @Autowired
        TranscriptStore transcriptStore;

        @TestConfiguration
        static class SilentModelConfig {

            @Bean
            @Primary
            ModelClient silentModelClient() {
                return request -> Flux.empty();
            }
        }

        @Test
        void emptyModelOutputDoesNotPersistAssistantMessage() {
            String sessionId = sessionService.create()
                .block(Duration.ofSeconds(5)).sessionId();

            sessionService.submitPrompt(sessionId, "hello")
                .blockLast(Duration.ofSeconds(5));

            List<Message> transcript =
                transcriptStore.load(sessionId, null).block(Duration.ofSeconds(5));

            assertThat(transcript).hasSize(1);
            assertThat(transcript.get(0)).isInstanceOf(UserMessage.class);
        }
    }

    @Nested
    @SpringBootTest
    class RespondingModelTests {

        @Autowired
        SessionService sessionService;

        @Autowired
        TranscriptStore transcriptStore;

        @TestConfiguration
        static class RespondingModelConfig {

            @Bean
            @Primary
            ModelClient respondingModelClient() {
                return request -> Flux.just(
                    new ModelStreamStartedEvent(request.model()),
                    new ModelTextDeltaEvent("response text"),
                    new ModelCompletedEvent()
                );
            }
        }

        @Test
        void assistantMessagePersistedImmediatelyAfterBlockLast() {
            String sessionId = sessionService.create()
                .block(Duration.ofSeconds(5)).sessionId();

            sessionService.submitPrompt(sessionId, "hello")
                .blockLast(Duration.ofSeconds(5));

            List<Message> transcript =
                transcriptStore.load(sessionId, null).block(Duration.ofSeconds(5));

            assertThat(transcript).hasSize(2);
            assertThat(transcript.get(0)).isInstanceOf(UserMessage.class);
            assertThat(((UserMessage) transcript.get(0)).content()).isEqualTo("hello");
            assertThat(transcript.get(1)).isInstanceOf(AssistantMessage.class);
            assertThat(((AssistantMessage) transcript.get(1)).textContent()).isEqualTo("response text");
        }

        @Test
        void replayAfterSubmitReturnsFullTurnWithoutDuplicates() {
            String sessionId = sessionService.create()
                .block(Duration.ofSeconds(5)).sessionId();

            sessionService.submitPrompt(sessionId, "replay test")
                .blockLast(Duration.ofSeconds(5));

            var page = sessionService.replay(sessionId, 0, 10)
                .block(Duration.ofSeconds(5));

            assertThat(page).isNotNull();
            assertThat(page.messages()).hasSize(2);
            assertThat(page.messages().get(0).message()).isInstanceOf(UserMessage.class);
            assertThat(((UserMessage) page.messages().get(0).message()).content())
                .isEqualTo("replay test");
            assertThat(page.messages().get(1).message()).isInstanceOf(AssistantMessage.class);
            assertThat(((AssistantMessage) page.messages().get(1).message()).textContent())
                .isEqualTo("response text");
        }

        @Test
        void liveStreamDoesNotLeakQueryTranscriptUpdateEvent() {
            String sessionId = sessionService.create()
                .block(Duration.ofSeconds(5)).sessionId();

            List<QueryEvent> streamEvents = new ArrayList<>();

            sessionService.stream(sessionId)
                .doOnNext(streamEvents::add)
                .subscribe();

            sessionService.submitPrompt(sessionId, "live stream test")
                .blockLast(Duration.ofSeconds(5));

            // QueryTranscriptUpdateEvent is never leaked to live subscribers
            assertThat(streamEvents)
                .noneMatch(e -> e instanceof QueryTranscriptUpdateEvent);

            // Live stream did receive at least StreamCompletedEvent
            assertThat(streamEvents)
                .anyMatch(e -> e instanceof SessionService.StreamCompletedEvent);

            // After stream completion, transcript is fully persisted without duplicates
            List<Message> transcript =
                transcriptStore.load(sessionId, null).block(Duration.ofSeconds(5));

            assertThat(transcript).hasSize(2);
            assertThat(transcript.get(0)).isInstanceOf(UserMessage.class);
            assertThat(((UserMessage) transcript.get(0)).content()).isEqualTo("live stream test");
            assertThat(transcript.get(1)).isInstanceOf(AssistantMessage.class);
            assertThat(((AssistantMessage) transcript.get(1)).textContent()).isEqualTo("response text");
        }

        @Test
        void twoTurnsDoNotDuplicateMessages() {
            String sessionId = sessionService.create()
                .block(Duration.ofSeconds(5)).sessionId();

            sessionService.submitPrompt(sessionId, "first")
                .blockLast(Duration.ofSeconds(5));

            sessionService.submitPrompt(sessionId, "second")
                .blockLast(Duration.ofSeconds(5));

            List<Message> transcript =
                transcriptStore.load(sessionId, null).block(Duration.ofSeconds(5));

            assertThat(transcript).hasSize(4);
            assertThat(transcript.get(0)).isInstanceOf(UserMessage.class);
            assertThat(((UserMessage) transcript.get(0)).content()).isEqualTo("first");
            assertThat(transcript.get(1)).isInstanceOf(AssistantMessage.class);
            assertThat(((AssistantMessage) transcript.get(1)).textContent()).isEqualTo("response text");
            assertThat(transcript.get(2)).isInstanceOf(UserMessage.class);
            assertThat(((UserMessage) transcript.get(2)).content()).isEqualTo("second");
            assertThat(transcript.get(3)).isInstanceOf(AssistantMessage.class);
            assertThat(((AssistantMessage) transcript.get(3)).textContent()).isEqualTo("response text");
        }
    }

    @Nested
    @SpringBootTest(properties = "app.tools.enabled=false")
    class ToolLoopTranscriptTests {

        @Autowired
        SessionService sessionService;

        @Autowired
        TranscriptStore transcriptStore;

        @TestConfiguration
        static class ToolLoopConfig {

            @Bean
            @Primary
            ModelClient toolLoopModelClient() {
                return request -> {
                    boolean hasToolResult = request.messages().stream()
                        .anyMatch(ToolResultMessage.class::isInstance);
                    if (!hasToolResult) {
                        return Flux.just(
                            new ModelTextDeltaEvent("let me check"),
                            new ModelToolUseEvent("call-1", "stub_tool", Map.of("x", 1)),
                            new ModelCompletedEvent()
                        );
                    }
                    return Flux.<com.clawcode.agent.model.ModelEvent>just(
                            new ModelTextDeltaEvent("final answer"),
                            new ModelCompletedEvent()
                        )
                        .delaySubscription(Duration.ofMillis(250));
                };
            }

            @Bean
            @Primary
            ToolExecutor stubToolExecutor() {
                return (req, ctx) -> Mono.just(
                    ToolResult.success(req.toolCallId(), req.toolName(), "ok"));
            }

            @Bean
            @Primary
            ToolRegistry stubToolRegistry() {
                ToolDefinition def = new ToolDefinition(
                    "stub_tool", "A test tool", Map.of("type", "object"));
                Tool tool = new Tool() {
                    @Override public String name() { return def.name(); }
                    @Override public ToolDefinition definition() { return def; }
                    @Override public Mono<Object> execute(Object input, Object context) {
                        return Mono.just("ok");
                    }
                };
                return new ToolRegistry() {
                    @Override public Optional<Tool> findByName(String name) {
                        return "stub_tool".equals(name) ? Optional.of(tool) : Optional.empty();
                    }
                    @Override public Set<String> listNames() { return Set.of("stub_tool"); }
                };
            }
        }

        @Test
        void transcriptContainsFullTurnContract() {
            String sessionId = sessionService.create()
                .block(Duration.ofSeconds(5)).sessionId();

            sessionService.submitPrompt(sessionId, "do something")
                .blockLast(Duration.ofSeconds(10));

            List<Message> transcript =
                transcriptStore.load(sessionId, null).block(Duration.ofSeconds(5));

            assertThat(transcript).hasSize(4);
            assertThat(transcript.get(0)).isInstanceOf(UserMessage.class);
            assertThat(((UserMessage) transcript.get(0)).content()).isEqualTo("do something");

            assertThat(transcript.get(1)).isInstanceOf(AssistantMessage.class);
            AssistantMessage firstAssistant = (AssistantMessage) transcript.get(1);
            assertThat(firstAssistant.textContent()).isEqualTo("let me check");
            assertThat(firstAssistant.content()).hasSize(2);
            assertThat(firstAssistant.content().get(0))
                .isInstanceOf(AssistantTextBlock.class);
            assertThat(((AssistantTextBlock) firstAssistant.content().get(0)).text())
                .isEqualTo("let me check");
            assertThat(firstAssistant.content().get(1))
                .isInstanceOf(AssistantToolUseBlock.class);

            assertThat(transcript.get(2)).isInstanceOf(ToolResultMessage.class);
            ToolResultMessage toolResult = (ToolResultMessage) transcript.get(2);
            assertThat(toolResult.toolName()).isEqualTo("stub_tool");

            assertThat(transcript.get(3)).isInstanceOf(AssistantMessage.class);
            AssistantMessage finalAssistant = (AssistantMessage) transcript.get(3);
            assertThat(finalAssistant.textContent()).isEqualTo("final answer");
        }

        @Test
        void toolResultIsPersistedBeforeFinalAssistantAndWithoutDuplicates() throws Exception {
            String sessionId = sessionService.create()
                .block(Duration.ofSeconds(5)).sessionId();

            CountDownLatch toolResultSeen = new CountDownLatch(1);
            CountDownLatch streamDone = new CountDownLatch(1);

            sessionService.submitPrompt(sessionId, "do something")
                .doOnNext(e -> {
                    if (e instanceof com.clawcode.agent.core.query.QueryToolResultEvent) {
                        toolResultSeen.countDown();
                    }
                })
                .doOnComplete(streamDone::countDown)
                .subscribe();

            assertThat(toolResultSeen.await(5, TimeUnit.SECONDS)).isTrue();

            List<Message> midTranscript = null;
            for (int i = 0; i < 50; i++) {
                midTranscript = transcriptStore.load(sessionId, null).block(Duration.ofSeconds(5));
                if (midTranscript != null && midTranscript.size() >= 3) {
                    break;
                }
                Thread.sleep(20);
            }

            assertThat(midTranscript).isNotNull();
            assertThat(midTranscript).hasSizeGreaterThanOrEqualTo(3);
            assertThat(midTranscript.get(0)).isInstanceOf(UserMessage.class);
            assertThat(midTranscript.get(1)).isInstanceOf(AssistantMessage.class);
            assertThat(midTranscript.get(2)).isInstanceOf(ToolResultMessage.class);

            assertThat(streamDone.await(5, TimeUnit.SECONDS)).isTrue();

            List<Message> finalTranscript =
                transcriptStore.load(sessionId, null).block(Duration.ofSeconds(5));
            assertThat(finalTranscript).hasSize(4);
            assertThat(finalTranscript.get(0)).isInstanceOf(UserMessage.class);
            assertThat(finalTranscript.get(1)).isInstanceOf(AssistantMessage.class);
            assertThat(finalTranscript.get(2)).isInstanceOf(ToolResultMessage.class);
            assertThat(finalTranscript.get(3)).isInstanceOf(AssistantMessage.class);
        }
    }

    @Nested
    @SpringBootTest(properties = "app.tools.enabled=false")
    class PostHookAttachmentTranscriptTests {

        @Autowired
        SessionService sessionService;

        @Autowired
        TranscriptStore transcriptStore;

        @TestConfiguration
        static class PostHookAttachmentConfig {
            private static final UserMessage HOOK_CONTEXT = new UserMessage(
                UUID.randomUUID(), Instant.now(), "hook context attachment");

            @Bean
            @Primary
            ModelClient toolLoopModelClient() {
                return request -> {
                    boolean hasToolResult = request.messages().stream()
                        .anyMatch(ToolResultMessage.class::isInstance);
                    if (!hasToolResult) {
                        return Flux.just(
                            new ModelTextDeltaEvent("checking"),
                            new ModelToolUseEvent("call-1", "stub_tool", Map.of("x", 1)),
                            new ModelCompletedEvent()
                        );
                    }
                    return Flux.just(
                        new ModelTextDeltaEvent("final answer"),
                        new ModelCompletedEvent()
                    );
                };
            }

            @Bean
            @Primary
            ToolExecutionHook postHookAttachment() {
                return TestToolExecutionHook.builder()
                    .postTool(context -> Mono.just(ToolPostHookResult.continueWith(
                        context.result(), List.of(HOOK_CONTEXT))))
                    .build();
            }

            @Bean
            @Primary
            ToolRegistry stubToolRegistry() {
                ToolDefinition def = new ToolDefinition(
                    "stub_tool", "A test tool", Map.of("type", "object"));
                Tool tool = new Tool() {
                    @Override public String name() { return def.name(); }
                    @Override public ToolDefinition definition() { return def; }
                    @Override public Mono<Object> execute(Object input, Object context) {
                        return Mono.just("ok");
                    }
                };
                return new ToolRegistry() {
                    @Override public Optional<Tool> findByName(String name) {
                        return "stub_tool".equals(name) ? Optional.of(tool) : Optional.empty();
                    }
                    @Override public Set<String> listNames() { return Set.of("stub_tool"); }
                };
            }

            static UserMessage hookContext() {
                return HOOK_CONTEXT;
            }
        }

        @Test
        void postHookContextIsPersistedInTranscriptAndReplayOnce() {
            String sessionId = sessionService.create()
                .block(Duration.ofSeconds(5)).sessionId();

            sessionService.submitPrompt(sessionId, "do something with hook")
                .blockLast(Duration.ofSeconds(10));

            List<Message> transcript =
                transcriptStore.load(sessionId, null).block(Duration.ofSeconds(5));

            assertThat(transcript).hasSize(5);
            assertThat(transcript.get(0)).isInstanceOf(UserMessage.class);
            assertThat(transcript.get(1)).isInstanceOf(AssistantMessage.class);
            assertThat(((AssistantMessage) transcript.get(1)).content())
                .anySatisfy(block -> assertThat(block).isInstanceOf(AssistantToolUseBlock.class));
            assertThat(transcript.get(2)).isInstanceOf(ToolResultMessage.class);
            assertThat(transcript.get(3)).isSameAs(PostHookAttachmentConfig.hookContext());
            assertThat(transcript.get(4)).isInstanceOf(AssistantMessage.class);
            assertThat(((AssistantMessage) transcript.get(4)).textContent()).isEqualTo("final answer");
            assertThat(transcript.stream()
                .filter(message -> message == PostHookAttachmentConfig.hookContext())
                .count()).isEqualTo(1);

            var page = sessionService.replay(sessionId, 0, 10)
                .block(Duration.ofSeconds(5));

            assertThat(page).isNotNull();
            assertThat(page.messages()).hasSize(5);
            assertThat(page.messages().get(1).message()).isInstanceOf(AssistantMessage.class);
            assertThat(page.messages().get(2).message()).isInstanceOf(ToolResultMessage.class);
            assertThat(page.messages().get(3).message()).isSameAs(PostHookAttachmentConfig.hookContext());
            assertThat(page.messages().get(4).message()).isInstanceOf(AssistantMessage.class);
            assertThat(page.messages().stream()
                .filter(row -> row.message() == PostHookAttachmentConfig.hookContext())
                .count()).isEqualTo(1);
        }
    }

    @Nested
    @SpringBootTest(properties = {
        "app.query.max-tool-rounds=2"
    })
    class MaxRoundTranscriptTests {

        @Autowired
        SessionService sessionService;

        @Autowired
        TranscriptStore transcriptStore;

        @TestConfiguration
        static class MaxRoundModelConfig {

            @Bean
            @Primary
            ModelClient toolOnlyModelClient() {
                return request -> Flux.just(
                    new ModelToolUseEvent("call-1", "stub_tool", Map.of("x", 1))
                );
            }

            @Bean
            @Primary
            ToolExecutor stubToolExecutor() {
                return (req, ctx) -> Mono.just(
                    ToolResult.success(req.toolCallId(), req.toolName(), "ok"));
            }

            @Bean
            @Primary
            ToolRegistry stubToolRegistry() {
                ToolDefinition def = new ToolDefinition(
                    "stub_tool", "A test tool", Map.of("type", "object"));
                Tool tool = new Tool() {
                    @Override public String name() { return def.name(); }
                    @Override public ToolDefinition definition() { return def; }
                    @Override public Mono<Object> execute(Object input, Object context) {
                        return Mono.just("ok");
                    }
                };
                return new ToolRegistry() {
                    @Override public Optional<Tool> findByName(String name) {
                        return "stub_tool".equals(name) ? Optional.of(tool) : Optional.empty();
                    }
                    @Override public Set<String> listNames() { return Set.of("stub_tool"); }
                };
            }
        }

        @Test
        void maxRoundPersistsAssistantOnceWithNoDuplicates() {
            String sessionId = sessionService.create()
                .block(Duration.ofSeconds(5)).sessionId();

            sessionService.submitPrompt(sessionId, "go")
                .blockLast(Duration.ofSeconds(10));

            List<Message> transcript =
                transcriptStore.load(sessionId, null).block(Duration.ofSeconds(5));

            assertThat(transcript).hasSize(6);
            assertThat(transcript.get(0)).isInstanceOf(UserMessage.class);
            assertThat(((UserMessage) transcript.get(0)).content()).isEqualTo("go");

            // maxToolRounds=2: 2 tool-using rounds + 1 max-rounds assistant
            for (int i = 1; i <= 2; i++) {
                assertThat(transcript.get(i * 2 - 1)).isInstanceOf(AssistantMessage.class);
                assertThat(transcript.get(i * 2)).isInstanceOf(ToolResultMessage.class);
            }

            assertThat(transcript.get(5)).isInstanceOf(AssistantMessage.class);
            String finalText = ((AssistantMessage) transcript.get(5)).textContent();
            assertThat(finalText).contains("maximum tool rounds");
        }

        @Test
        void maxRoundReplayReturnsNoDuplicates() {
            String sessionId = sessionService.create()
                .block(Duration.ofSeconds(5)).sessionId();

            sessionService.submitPrompt(sessionId, "replay check")
                .blockLast(Duration.ofSeconds(10));

            var page = sessionService.replay(sessionId, 0, 10)
                .block(Duration.ofSeconds(5));

            assertThat(page).isNotNull();
            assertThat(page.messages()).hasSize(6);
            assertThat(page.messages().get(0).message()).isInstanceOf(UserMessage.class);
            assertThat(page.messages().get(5).message()).isInstanceOf(AssistantMessage.class);
            assertThat(((AssistantMessage) page.messages().get(5).message()).textContent())
                .contains("maximum tool rounds");
        }
    }
}
