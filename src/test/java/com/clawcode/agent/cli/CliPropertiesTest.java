package com.clawcode.agent.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliPropertiesTest {

    @Test
    void defaults() {
        var p = new CliProperties(null, null, null, 0, 0);
        assertThat(p.baseUrl()).isEqualTo("http://localhost:8080");
        assertThat(p.apiKeyHeader()).isEqualTo("X-API-Key");
        assertThat(p.apiKey()).isNull();
        assertThat(p.timeoutMs()).isEqualTo(30000);
        assertThat(p.streamReadTimeoutMs()).isEqualTo(300000);
    }

    @Test
    void explicitValues() {
        var p = new CliProperties("http://remote:9090", "X-Token", "secret", 5000, 60000);
        assertThat(p.baseUrl()).isEqualTo("http://remote:9090");
        assertThat(p.apiKeyHeader()).isEqualTo("X-Token");
        assertThat(p.apiKey()).isEqualTo("secret");
        assertThat(p.timeoutMs()).isEqualTo(5000);
        assertThat(p.streamReadTimeoutMs()).isEqualTo(60000);
    }

    @Test
    void blankBaseUrl_fallsBackToDefault() {
        var p = new CliProperties("  ", null, null, 0, 0);
        assertThat(p.baseUrl()).isEqualTo("http://localhost:8080");
    }

    @Test
    void missingScheme_rejected() {
        assertThatThrownBy(() -> new CliProperties("localhost:8080", null, null, 0, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("app.cli.base-url");
    }

    @Test
    void missingHost_rejected() {
        assertThatThrownBy(() -> new CliProperties("http://", null, null, 0, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("app.cli.base-url");
    }

    @Test
    void nullTimeouts_corrected() {
        var p = new CliProperties(null, null, null, -1, -1);
        assertThat(p.timeoutMs()).isEqualTo(30000);
        assertThat(p.streamReadTimeoutMs()).isEqualTo(300000);
    }
}
