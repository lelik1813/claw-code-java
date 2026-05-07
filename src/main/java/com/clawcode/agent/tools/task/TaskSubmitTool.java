package com.clawcode.agent.tools.task;

import com.clawcode.agent.core.tasks.TaskRecord;
import com.clawcode.agent.core.tasks.TaskService;
import com.clawcode.agent.tools.Tool;
import com.clawcode.agent.tools.ToolDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnBean(TaskService.class)
public class TaskSubmitTool implements Tool {

    private final TaskService taskService;

    public TaskSubmitTool(TaskService taskService) {
        this.taskService = taskService;
    }

    private static final ToolDefinition DEFINITION = new ToolDefinition(
        "task_submit",
        "Submit a background task for asynchronous execution. "
            + "Returns an object with 'task_id' and 'status' immediately; "
            + "use task_status or task_result to check on progress.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "session_id", Map.of("type", "string",
                    "description", "Session ID to associate with the task."),
                "turn_id", Map.of("type", "string",
                    "description", "Optional turn ID for correlation."),
                "input", Map.of("type", "string",
                    "description", "Task input or prompt to execute.")
            ),
            "required", List.of("session_id", "input"),
            "additionalProperties", false
        )
    );

    @Override
    public String name() {
        return "task_submit";
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Mono<Object> execute(Object input, Object context) {
        Map<String, String> params = extractParams(input);
        String sessionId = params.get("session_id");
        String turnId = params.get("turn_id");
        String taskInput = params.get("input");

        if (sessionId == null || sessionId.isBlank()) {
            return Mono.error(new IllegalArgumentException("session_id is required"));
        }
        if (taskInput == null || taskInput.isBlank()) {
            return Mono.error(new IllegalArgumentException("input is required"));
        }

        return taskService.submit(sessionId, turnId, taskInput)
            .map(TaskSubmitTool::toMap);
    }

    private Map<String, String> extractParams(Object input) {
        if (input instanceof Map<?, ?> m) {
            Map<String, String> result = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                if (v != null) result.put(k.toString(), v.toString());
            });
            return result;
        }
        return Map.of();
    }

    private static Map<String, Object> toMap(TaskRecord task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("task_id", task.taskId());
        map.put("status", task.status().name());
        return map;
    }
}
