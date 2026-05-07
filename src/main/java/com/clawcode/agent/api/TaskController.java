package com.clawcode.agent.api;

import com.clawcode.agent.api.dto.SubmitTaskRequest;
import com.clawcode.agent.api.dto.SubmitTaskResponse;
import com.clawcode.agent.api.dto.TaskResultResponse;
import com.clawcode.agent.api.dto.TaskStatusResponse;
import com.clawcode.agent.core.tasks.TaskRecord;
import com.clawcode.agent.core.tasks.TaskService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@ConditionalOnBean(TaskService.class)
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/api/tasks")
    public Mono<ResponseEntity<SubmitTaskResponse>> submit(
        @Valid @RequestBody SubmitTaskRequest request
    ) {
        return taskService.submit(request.sessionId(), request.turnId(), request.input())
            .map(task -> ResponseEntity.accepted().body(
                new SubmitTaskResponse(task.taskId(), task.status().name(), task.createdAt())));
    }

    @GetMapping("/api/tasks/{taskId}")
    public Mono<ResponseEntity<TaskStatusResponse>> getStatus(
        @PathVariable String taskId
    ) {
        return taskService.getStatus(taskId)
            .map(task -> ResponseEntity.ok(toStatusResponse(task)));
    }

    @GetMapping("/api/tasks/{taskId}/result")
    public Mono<ResponseEntity<TaskResultResponse>> getResult(
        @PathVariable String taskId
    ) {
        return taskService.getResult(taskId)
            .map(task -> ResponseEntity.ok(toResultResponse(task)));
    }

    @PostMapping("/api/tasks/{taskId}/cancel")
    public Mono<ResponseEntity<Void>> cancel(
        @PathVariable String taskId
    ) {
        return taskService.cancel(taskId)
            .thenReturn(ResponseEntity.accepted().build());
    }

    private static TaskStatusResponse toStatusResponse(TaskRecord task) {
        return new TaskStatusResponse(
            task.taskId(), task.status().name(), task.updatedAt(), task.error());
    }

    private static TaskResultResponse toResultResponse(TaskRecord task) {
        return new TaskResultResponse(
            task.taskId(), task.status().name(), task.output(), task.error());
    }
}
