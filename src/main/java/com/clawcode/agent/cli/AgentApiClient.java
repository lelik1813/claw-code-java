package com.clawcode.agent.cli;

import java.util.List;
import com.clawcode.agent.cli.model.CliQueryEvent;
import com.clawcode.agent.cli.model.MessageAck;
import com.clawcode.agent.cli.model.ReplayPage;
import com.clawcode.agent.cli.model.SessionInfo;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Thin client over the claw-code-java REST/SSE API.
 * Decouples CLI commands from HTTP transport details.
 */
public interface AgentApiClient {

    /** POST /api/sessions — create a new session. */
    Mono<SessionInfo> createSession();

    /** POST /api/sessions/{sessionId}/messages — submit a prompt. */
    Mono<MessageAck> sendMessage(String sessionId, String content, List<String> skillIds);

    /** GET /api/sessions/{sessionId}/stream — attach to SSE event stream. */
    Flux<CliQueryEvent> attachStream(String sessionId);

    /** GET /api/sessions/{sessionId}/replay — fetch transcript history. */
    Mono<ReplayPage> replay(String sessionId, int after, int limit);

    /** POST /api/tasks — submit a background task. */
    Mono<AgentApiDtos.TaskSubmitResult> submitTask(AgentApiDtos.SubmitTaskRequest request);

    /** GET /api/tasks/{taskId} — get task status. */
    Mono<AgentApiDtos.TaskStatus> taskStatus(String taskId);

    /** GET /api/tasks/{taskId}/result — get task result. */
    Mono<AgentApiDtos.TaskResult> taskResult(String taskId);
}
