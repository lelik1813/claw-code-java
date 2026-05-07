package com.clawcode.agent.config;

import com.clawcode.agent.core.tasks.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.tasks", name = "enabled", havingValue = "true")
public class TasksConfiguration {

    @Bean
    public TaskService taskService(TaskProperties properties) {
        TaskExecutor executor = createExecutor(properties);
        return new TaskService(executor);
    }

    private TaskExecutor createExecutor(TaskProperties properties) {
        if (properties.backend() == TaskProperties.TaskBackend.REMOTE) {
            TaskProperties.Remote remote = properties.remote();
            if (remote.baseUrl() == null || remote.baseUrl().isBlank()) {
                throw new IllegalStateException(
                    "Remote task backend requires non-blank 'app.tasks.remote.base-url'");
            }
            RemoteTaskClient client = new HttpRemoteTaskClient(
                remote.baseUrl(), remote.authToken(), remote.timeoutMs());
            return new RemoteTaskExecutor(client, properties.defaultTimeoutMs());
        }
        return new InMemoryTaskExecutor(input ->
            reactor.core.publisher.Mono.just("completed: " + input));
    }
}
