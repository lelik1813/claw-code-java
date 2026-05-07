package com.clawcode.agent.forensics;

import com.clawcode.agent.core.query.QueryEvent;
import com.clawcode.agent.core.session.SessionService;
import com.clawcode.agent.model.*;
import com.clawcode.agent.persistence.TranscriptStore;
import com.clawcode.agent.tools.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AuditTrailCorrelationTest {

    @Autowired
    SessionService sessionService;

    @Autowired
    TranscriptStore transcriptStore;

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        AuditTrail capturingAuditTrail() {
            return event -> Mono.fromRunnable(() -> capturedEvents.add(event));
        }

        @Bean
        @Primary
        ModelClient toolCallingModelClient() {
            return request -> {
                int call = callCount.getAndIncrement();
                if (call == 0) {
                    return Flux.just(
                        new ModelStreamStartedEvent("test-model"),
                        new ModelToolUseEvent("call-1", firstCallToolName, firstCallToolInput)
                    );
                }
                return Flux.just(
                    new ModelStreamStartedEvent("test-model"),
                    new ModelTextDeltaEvent("done"),
                    new ModelCompletedEvent()
                );
            };
        }

        @Bean
        @Primary
        ToolExecutor echoToolExecutor(SpringToolRegistry registry) {
            return new DefaultToolExecutor(registry,
                (req, ctx) -> policy.decide(req, ctx),
                capturingAuditTrail(),
                new ObservabilityMetrics(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                List.of());
        }

        @Bean
        @Primary
        SpringToolRegistry toolRegistry() {
            return new SpringToolRegistry(List.of(new EchoTool(), new StubFileWriteTool()));
        }
    }

    static final List<AuditEvent> capturedEvents = new CopyOnWriteArrayList<>();
    static final AtomicInteger callCount = new AtomicInteger();
    static volatile String firstCallToolName = "echo";
    static volatile Object firstCallToolInput = "audit-test";
    static volatile ToolPermissionPolicy policy =
        (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow());

    @org.junit.jupiter.api.BeforeEach
    void reset() {
        capturedEvents.clear();
        callCount.set(0);
        firstCallToolName = "echo";
        firstCallToolInput = "audit-test";
        policy = (req, ctx) -> Mono.just(new ToolPermissionDecision.Allow());
    }

    @Test
    void allAuditEventsShareSameSessionIdAndTurnId() {
        String sessionId = sessionService.create()
            .block(Duration.ofSeconds(5)).sessionId();

        sessionService.submitPrompt(sessionId, "audit me")
            .blockLast(Duration.ofSeconds(5));

        assertThat(capturedEvents).isNotEmpty();

        Set<String> sessionIds = new HashSet<>();
        Set<String> turnIds = new HashSet<>();
        for (AuditEvent e : capturedEvents) {
            sessionIds.add(e.sessionId());
            if (e.turnId() != null) {
                turnIds.add(e.turnId());
            }
        }

        assertThat(sessionIds).as("all events share same sessionId")
            .hasSize(1)
            .containsExactly(sessionId);
        assertThat(turnIds).as("all events with turnId share same value")
            .hasSize(1);
    }

    @Test
    void auditCoversAllStages() {
        String sessionId = sessionService.create()
            .block(Duration.ofSeconds(5)).sessionId();

        sessionService.submitPrompt(sessionId, "audit me")
            .blockLast(Duration.ofSeconds(5));

        Set<String> eventTypes = new TreeSet<>();
        for (AuditEvent e : capturedEvents) {
            eventTypes.add(e.eventType());
        }

        assertThat(eventTypes).as("persistence stage covered")
            .contains("transcript.load.start", "transcript.load.end",
                "transcript.append.start", "transcript.append.end");

        assertThat(eventTypes).as("model stage covered")
            .contains("model.request.sent");

        assertThat(eventTypes).as("tool permission stage covered")
            .contains("tool.permission.allowed");

        assertThat(eventTypes).as("tool execution stage covered")
            .contains("tool.execution.success");

        assertThat(eventTypes).as("tool orchestration stage covered")
            .contains("tool.requested", "tool.result.received");

        assertThat(eventTypes).as("turn completion covered")
            .contains("turn.completed");
    }

    @Test
    void turnCompletedCarriesRoundCounters() {
        String sessionId = sessionService.create()
            .block(Duration.ofSeconds(5)).sessionId();

        sessionService.submitPrompt(sessionId, "audit me")
            .blockLast(Duration.ofSeconds(5));

        AuditEvent turnCompleted = capturedEvents.stream()
            .filter(e -> "turn.completed".equals(e.eventType()))
            .reduce((first, second) -> second)
            .orElseThrow();

        Map<String, Object> attrs = turnCompleted.attributes();
        assertThat(attrs).containsEntry("round", 1);
        assertThat(attrs).containsKey("messageCount");
        assertThat(attrs).containsKey("assistantMessagesCount");
        assertThat(attrs).containsKey("toolUseCount");
        assertThat(attrs).containsKey("toolResultsCount");
        assertThat((long) attrs.get("assistantMessagesCount")).isGreaterThanOrEqualTo(1);
        assertThat((long) attrs.get("toolUseCount")).isGreaterThanOrEqualTo(1);
        assertThat((long) attrs.get("toolResultsCount")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void modelRequestSentCarriesHistoryCounts() {
        String sessionId = sessionService.create()
            .block(Duration.ofSeconds(5)).sessionId();

        sessionService.submitPrompt(sessionId, "audit me")
            .blockLast(Duration.ofSeconds(5));

        List<AuditEvent> requestEvents = capturedEvents.stream()
            .filter(e -> "model.request.sent".equals(e.eventType()))
            .toList();

        assertThat(requestEvents).hasSizeGreaterThanOrEqualTo(2);

        // round 0: only the user message
        Map<String, Object> round0 = requestEvents.get(0).attributes();
        assertThat(round0).containsKey("messageCount");
        assertThat(round0).containsKey("assistantMessagesCount");
        assertThat(round0).containsKey("toolUseCount");
        assertThat(round0).containsKey("toolResultsCount");

        // round 1: has assistant + tool result from round 0
        Map<String, Object> round1 = requestEvents.get(1).attributes();
        assertThat((long) round1.get("assistantMessagesCount")).isGreaterThanOrEqualTo(1);
        assertThat((long) round1.get("toolUseCount")).isGreaterThanOrEqualTo(1);
        assertThat((long) round1.get("toolResultsCount")).isGreaterThanOrEqualTo(1);
        assertThat((int) round1.get("messageCount"))
            .isGreaterThan((int) round0.get("messageCount"));
    }

    @Test
    void persistenceEventsCarryTurnId() {
        String sessionId = sessionService.create()
            .block(Duration.ofSeconds(5)).sessionId();

        sessionService.submitPrompt(sessionId, "audit me")
            .blockLast(Duration.ofSeconds(5));

        List<AuditEvent> persistenceEvents = capturedEvents.stream()
            .filter(e -> e.eventType().startsWith("transcript."))
            .filter(e -> e.turnId() != null)
            .toList();

        assertThat(persistenceEvents).as("persistence events have non-null turnId")
            .isNotEmpty();

        String expectedTurnId = capturedEvents.stream()
            .filter(e -> "turn.completed".equals(e.eventType()))
            .map(AuditEvent::turnId)
            .filter(Objects::nonNull)
            .findFirst()
            .orElseThrow();

        assertThat(persistenceEvents)
            .allSatisfy(e -> assertThat(e.turnId())
                .as("persistence event %s turnId", e.eventType())
                .isEqualTo(expectedTurnId));
    }

    @Test
    void deniedToolProducesAuditEvent() {
        firstCallToolName = "file_write";
        firstCallToolInput = "test input";
        policy = (req, ctx) -> Mono.just(new ToolPermissionDecision.Deny("test deny reason"));

        String sessionId = sessionService.create()
            .block(Duration.ofSeconds(5)).sessionId();

        sessionService.submitPrompt(sessionId, "write something")
            .blockLast(Duration.ofSeconds(5));

        AuditEvent deniedEvent = capturedEvents.stream()
            .filter(e -> "tool.permission.denied".equals(e.eventType()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected tool.permission.denied event"));

        assertThat(deniedEvent.sessionId()).isEqualTo(sessionId);
        assertThat(deniedEvent.turnId()).isNotNull();
        assertThat(deniedEvent.attributes())
            .containsEntry("toolName", "file_write")
            .containsEntry("toolCallId", "call-1")
            .containsEntry("policyReason", "test deny reason")
            .containsKey("reason");
    }

    @Test
    void unknownToolProducesAuditEvent() {
        firstCallToolName = "ghost_tool";
        firstCallToolInput = null;

        String sessionId = sessionService.create()
            .block(Duration.ofSeconds(5)).sessionId();

        sessionService.submitPrompt(sessionId, "use ghost tool")
            .blockLast(Duration.ofSeconds(5));

        AuditEvent unknownEvent = capturedEvents.stream()
            .filter(e -> "tool.permission.unknown".equals(e.eventType()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected tool.permission.unknown event"));

        assertThat(unknownEvent.sessionId()).isEqualTo(sessionId);
        assertThat(unknownEvent.turnId()).isNotNull();
        assertThat(unknownEvent.attributes())
            .containsEntry("toolName", "ghost_tool")
            .containsEntry("toolCallId", "call-1")
            .containsKey("reason");
    }

    private static class StubFileWriteTool implements Tool {
        @Override public String name() { return "file_write"; }
        @Override public ToolDefinition definition() {
            return new ToolDefinition(name(), "test stub", Map.of());
        }
        @Override public Mono<Object> execute(Object input, Object context) {
            return Mono.just("stub");
        }
    }

    private static class EchoTool implements Tool {
        @Override
        public String name() { return "echo"; }

        @Override
        public ToolDefinition definition() { return new ToolDefinition(name(), "test tool", Map.of()); }

        @Override
        public Mono<Object> execute(Object input, Object context) {
            return Mono.just("echo: " + input);
        }
    }
}
