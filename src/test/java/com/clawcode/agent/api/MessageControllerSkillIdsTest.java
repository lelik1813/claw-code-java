package com.clawcode.agent.api;

import com.clawcode.agent.api.dto.SubmitMessageRequest;
import com.clawcode.agent.api.dto.SubmitMessageResponse;
import com.clawcode.agent.core.session.SessionRecord;
import com.clawcode.agent.core.session.SessionService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageControllerSkillIdsTest {

    @Mock
    private SessionService sessionService;

    private MessageController controller;

    @BeforeEach
    void setUp() {
        controller = new MessageController(sessionService);
    }

    @Test
    void submitPassesSkillIdsToSessionService() {
        String sessionId = "session-1";
        List<String> skillIds = List.of("skill-a", "skill-b");
        SubmitMessageRequest request = new SubmitMessageRequest("hello", skillIds);

        when(sessionService.get(sessionId))
            .thenReturn(Mono.just(new SessionRecord(sessionId, Instant.now())));
        when(sessionService.submitPrompt(sessionId, "hello", skillIds))
            .thenReturn(Flux.empty());

        ResponseEntity<SubmitMessageResponse> response = controller.submit(sessionId, request)
            .block(Duration.ofSeconds(1));

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isEqualTo(new SubmitMessageResponse(sessionId, true));
        verify(sessionService).submitPrompt(sessionId, "hello", skillIds);
    }
}
