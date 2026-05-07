package com.clawcode.agent.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

public class R2dbcPropertyPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String backend = resolveBackend(env);

        if ("r2dbc".equalsIgnoreCase(backend)) {
            addDbProperties(env);
        }
    }

    private void addDbProperties(ConfigurableEnvironment env) {
        String host = env.getProperty("POSTGRES_HOST", "localhost");
        String port = env.getProperty("POSTGRES_PORT", "5432");
        String db = env.getProperty("POSTGRES_DB", "agent_server");
        String user = env.getProperty("POSTGRES_USER", "agent_server");
        String password = env.getProperty("POSTGRES_PASSWORD", "");
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        String r2dbcUrl = "r2dbc:postgresql://" + host + ":" + port + "/" + db;

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("spring.datasource.url", jdbcUrl);
        props.put("spring.datasource.username", user);
        props.put("spring.datasource.password", password);
        props.put("spring.datasource.hikari.maximum-pool-size", "3");
        props.put("spring.r2dbc.url", r2dbcUrl);
        props.put("spring.r2dbc.username", user);
        props.put("spring.r2dbc.password", password);
        props.put("spring.flyway.enabled", env.getProperty("FLYWAY_ENABLED", "true"));
        props.put("spring.flyway.url", jdbcUrl);
        props.put("spring.flyway.user", env.getProperty("FLYWAY_USER", user));
        props.put("spring.flyway.password", env.getProperty("FLYWAY_PASSWORD", password));
        props.put("spring.flyway.locations", "classpath:db/migration");

        MapPropertySource source = new MapPropertySource("persistence-r2dbc", props);
        if (env.getPropertySources().contains("dotenv")) {
            env.getPropertySources().addAfter("dotenv", source);
        } else {
            env.getPropertySources().addLast(source);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    private String resolveBackend(ConfigurableEnvironment env) {
        String fromLegacyEnv = env.getProperty("PERSISTENCE_BACKEND");
        if (StringUtils.hasText(fromLegacyEnv)) {
            return fromLegacyEnv;
        }

        String fromSpringBinding = env.getProperty("app.persistence.backend");
        if (StringUtils.hasText(fromSpringBinding)) {
            return fromSpringBinding;
        }

        return "in-memory";
    }
}
