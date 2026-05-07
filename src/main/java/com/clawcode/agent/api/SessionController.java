package com.clawcode.agent.api;

import com.clawcode.agent.api.dto.CreateSessionResponse;
import com.clawcode.agent.api.dto.ReplayMessage;
import com.clawcode.agent.api.dto.ReplayResponse;
import com.clawcode.agent.api.dto.SessionResponse;
import com.clawcode.agent.core.session.SessionService;
import com.clawcode.agent.persistence.TranscriptStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private static final int DEFAULT_REPLAY_LIMIT = 100;

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public Mono<ResponseEntity<CreateSessionResponse>> create() {
        return sessionService.create()
            .map(record -> ResponseEntity.ok(
                new CreateSessionResponse(record.sessionId(), record.createdAt())
            ));
    }

    @GetMapping("/{sessionId}")
    public Mono<ResponseEntity<SessionResponse>> get(@PathVariable String sessionId) {
        return sessionService.get(sessionId)
            .map(record -> ResponseEntity.ok(
                new SessionResponse(record.sessionId(), record.createdAt())
            ))
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/{sessionId}/replay")
    public Mono<ResponseEntity<ReplayResponse>> replay(
        @PathVariable String sessionId,
        @RequestParam(defaultValue = "0") int after,
        @RequestParam(defaultValue = "100") int limit
    ) {
        int effectiveLimit = limit > 0 && limit <= 1000 ? limit : DEFAULT_REPLAY_LIMIT;
        return sessionService.replay(sessionId, after, effectiveLimit)
            .map(page -> ResponseEntity.ok(new ReplayResponse(
                page.messages().stream()
                    .map(sm -> ReplayMessage.from(sm.message()))
                    .toList(),
                page.nextCursor(),
                page.hasMore()
            )))
            .onErrorResume(IllegalArgumentException.class,
                e -> Mono.just(ResponseEntity.notFound().build()));
    }
}
