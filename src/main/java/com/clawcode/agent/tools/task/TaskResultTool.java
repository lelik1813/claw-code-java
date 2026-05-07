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
public class TaskResultTool implements Tool {

    private final TaskService taskService;

    public TaskResultTool(TaskService taskService) {
        this.taskService = taskService;
    }

    private static final ToolDefinition DEFINITION = new ToolDefinition(
        "task_result",
        "Retrieve the result of a completed background task. "
            + "Returns an object with 'task_id', 'status', 'output', and optionally 'error'.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "task_id", Map.of("type", "string",
                    "description", "ID of the completed task, as returned by task_submit.")
            ),
            "required", List.of("task_id"),
            "additionalProperties", false
        )
    );

    @Override
    public String name() {
        return "task_result";
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Mono<Object> execute(Object input, Object context) {
        Map<String, String> params = extractParams(input);
        String taskId = params.get("task_id");

        if (taskId == null || taskId.isBlank()) {
            return Mono.error(new IllegalArgumentException("task_id is required"));
        }

        return taskService.getResult(taskId).map(TaskResultTool::toMap);
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
        map.put("output", task.output());
        if (task.error() != null) {
            map.put("error", task.error());
        }
        return map;
    }
}
