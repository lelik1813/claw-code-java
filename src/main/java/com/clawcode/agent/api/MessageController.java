package com.clawcode.agent.api;

import com.clawcode.agent.api.dto.SubmitMessageRequest;
import com.clawcode.agent.api.dto.SubmitMessageResponse;
import com.clawcode.agent.core.session.SessionService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@RestController
public class MessageController {

    private final SessionService sessionService;

    public MessageController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("/api/sessions/{sessionId}/messages")
    public Mono<ResponseEntity<SubmitMessageResponse>> submit(
        @PathVariable String sessionId,
        @Valid @RequestBody SubmitMessageRequest request
    ) {
        return sessionService.get(sessionId)
            .switchIfEmpty(Mono.error(() -> new IllegalArgumentException(
                "Session not found: " + sessionId
            )))
            .flatMap(record -> {
                sessionService.submitPrompt(sessionId, request.content(), request.skillIds())
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                        event -> {},
                        ex -> log.error("Orchestration failed for session={}: {}", sessionId, ex.getMessage(), ex),
                        () -> log.debug("Orchestration completed for session={}", sessionId)
                    );
                return Mono.just(ResponseEntity.accepted().body(
                    new SubmitMessageResponse(sessionId, true)
                ));
            });
    }
}
