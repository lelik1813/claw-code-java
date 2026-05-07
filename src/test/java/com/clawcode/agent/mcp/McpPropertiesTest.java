package com.clawcode.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpPropertiesTest {

    @Test
    void defaultTransportTypeIsHttp() {
        var def = new McpProperties.McpServerDefinition(true, "http://localhost", "");
        assertThat(def.type()).isEqualTo(McpTransportType.HTTP);
    }

    @Test
    void backwardCompatConstructorSetsDefaults() {
        var def = new McpProperties.McpServerDefinition(true, "http://localhost:3001", "tok");

        assertThat(def.enabled()).isTrue();
        assertThat(def.baseUrl()).isEqualTo("http://localhost:3001");
        assertThat(def.authToken()).isEqualTo("tok");
        assertThat(def.type()).isEqualTo(McpTransportType.HTTP);
        assertThat(def.command()).isNull();
        assertThat(def.args()).isEmpty();
        assertThat(def.env()).isEmpty();
        assertThat(def.workingDir()).isNull();
        assertThat(def.startupTimeoutMs()).isEqualTo(10_000);
        assertThat(def.headers()).isEmpty();
        assertThat(def.connectTimeoutMs()).isEqualTo(30_000);
        assertThat(def.readTimeoutMs()).isEqualTo(300_000);
    }

    @Test
    void fullStdioConfig() {
        var def = new McpProperties.McpServerDefinition(
            true, McpTransportType.STDIO, null, "",
            "npx", List.of("-y", "@mcp/server"), Map.of("NODE_ENV", "production"),
            "/tmp", 15_000, null, 30_000, 300_000);

        assertThat(def.type()).isEqualTo(McpTransportType.STDIO);
        assertThat(def.command()).isEqualTo("npx");
        assertThat(def.args()).containsExactly("-y", "@mcp/server");
        assertThat(def.env()).containsEntry("NODE_ENV", "production");
        assertThat(def.workingDir()).isEqualTo("/tmp");
        assertThat(def.startupTimeoutMs()).isEqualTo(15_000);
    }

    @Test
    void fullSseConfig() {
        var def = new McpProperties.McpServerDefinition(
            true, McpTransportType.SSE, "http://localhost:3002/sse", "",
            null, null, null, null, 10_000,
            Map.of("Authorization", "Bearer token123"), 5_000, 120_000);

        assertThat(def.type()).isEqualTo(McpTransportType.SSE);
        assertThat(def.baseUrl()).isEqualTo("http://localhost:3002/sse");
        assertThat(def.headers()).containsEntry("Authorization", "Bearer token123");
        assertThat(def.connectTimeoutMs()).isEqualTo(5_000);
        assertThat(def.readTimeoutMs()).isEqualTo(120_000);
    }

    @Test
    void nullAuthTokenDefaultsToEmpty() {
        var def = new McpProperties.McpServerDefinition(true, "http://localhost", null);
        assertThat(def.authToken()).isEmpty();
    }

    @Test
    void nullCollectionsDefaultToEmpty() {
        var def = new McpProperties.McpServerDefinition(
            true, McpTransportType.STDIO, null, "",
            "cmd", null, null, null, 10_000, null, 30_000, 300_000);

        assertThat(def.args()).isEmpty();
        assertThat(def.env()).isEmpty();
        assertThat(def.headers()).isEmpty();
    }

    @Test
    void serversMapDefaultsToEmpty() {
        var props = new McpProperties(false, null);
        assertThat(props.servers()).isEmpty();
    }

    @Test
    void disabledServerDefinition() {
        var def = new McpProperties.McpServerDefinition(false, "http://localhost", "");
        assertThat(def.enabled()).isFalse();
    }

    @Test
    void allTransportTypes() {
        assertThat(McpTransportType.values()).containsExactly(
            McpTransportType.HTTP, McpTransportType.STDIO, McpTransportType.SSE);
    }
}
