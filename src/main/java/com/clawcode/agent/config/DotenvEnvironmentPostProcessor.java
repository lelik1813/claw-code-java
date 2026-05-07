package com.clawcode.agent.config;

import io.github.cdimascio.dotenv.Dotenv;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();

        Map<String, Object> map = new LinkedHashMap<>();
        dotenv.entries().forEach(entry -> map.put(entry.getKey(), entry.getValue()));

        if (!map.isEmpty()) {
            environment.getPropertySources().addLast(
                new MapPropertySource("dotenv", map));
            Object allowedRoots = map.get("APP_TOOLS_ALLOWED_ROOTS");
            if (allowedRoots instanceof String roots && !roots.isBlank()
                && System.getProperty("app.tools.allowed-roots") == null) {
                System.setProperty("app.tools.allowed-roots", roots);
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
