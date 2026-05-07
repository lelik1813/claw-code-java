package com.clawcode.agent.cli.mcp;

import com.clawcode.agent.cli.mcp.McpServerConfig.McpTransport;
import com.clawcode.agent.cli.mcp.McpServerConfig.ValidationException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpServerConfigTest {

    // ── transport parsing ───────────────────────────────────

    @Nested
    class TransportParsing {

        @Test
        void httpParsed() {
            assertThat(McpTransport.parse("HTTP")).isEqualTo(McpTransport.HTTP);
        }

        @Test
        void caseInsensitive() {
            assertThat(McpTransport.parse("http")).isEqualTo(McpTransport.HTTP);
            assertThat(McpTransport.parse("Stdio")).isEqualTo(McpTransport.STDIO);
            assertThat(McpTransport.parse("sse")).isEqualTo(McpTransport.SSE);
        }

        @Test
        void invalidThrows() {
            assertThatThrownBy(() -> McpTransport.parse("BOGUS"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("invalid transport");
        }

        @Test
        void nullThrows() {
            assertThatThrownBy(() -> McpTransport.parse(null))
                .isInstanceOf(ValidationException.class);
        }
    }

    // ── name validation ─────────────────────────────────────

    @Nested
    class NameValidation {

        @Test
        void validNames() {
            McpServerConfig.validateName("my-server");
            McpServerConfig.validateName("Server1");
            McpServerConfig.validateName("a");
            McpServerConfig.validateName("abc_def-123");
        }

        @Test
        void nullRejected() {
            assertThatThrownBy(() -> McpServerConfig.validateName(null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("server name must not be blank");
        }

        @Test
        void blankRejected() {
            assertThatThrownBy(() -> McpServerConfig.validateName("   "))
                .isInstanceOf(ValidationException.class)
                .hasMessage("server name must not be blank");
        }

        @Test
        void startsWithDigitRejected() {
            assertThatThrownBy(() -> McpServerConfig.validateName("1server"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("invalid server name");
        }

        @Test
        void specialCharsRejected() {
            assertThatThrownBy(() -> McpServerConfig.validateName("my.server"))
                .isInstanceOf(ValidationException.class);
        }
    }

    // ── full config validation ──────────────────────────────

    @Nested
    class ConfigValidation {

        @Test
        void validHttpConfig() {
            var config = new McpServerConfig("my-server", McpTransport.HTTP,
                "http://localhost:3000", null, null, null, null, true);
            McpServerConfig.validate(config);
        }

        @Test
        void validStdioConfig() {
            var config = new McpServerConfig("my-server", McpTransport.STDIO,
                null, "npx", null, null, null, true);
            McpServerConfig.validate(config);
        }

        @Test
        void validSseConfig() {
            var config = new McpServerConfig("my-server", McpTransport.SSE,
                "http://localhost:3001/events", null, null, null, null, true);
            McpServerConfig.validate(config);
        }

        @Test
        void httpMissingUrl() {
            var config = new McpServerConfig("my-server", McpTransport.HTTP,
                null, null, null, null, null, true);
            assertThatThrownBy(() -> McpServerConfig.validate(config))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("url is required for HTTP transport");
        }

        @Test
        void sseMissingUrl() {
            var config = new McpServerConfig("my-server", McpTransport.SSE,
                "  ", null, null, null, null, true);
            assertThatThrownBy(() -> McpServerConfig.validate(config))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("url is required for SSE transport");
        }

        @Test
        void stdioMissingCommand() {
            var config = new McpServerConfig("my-server", McpTransport.STDIO,
                null, null, null, null, null, true);
            assertThatThrownBy(() -> McpServerConfig.validate(config))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("command is required for STDIO transport");
        }

        @Test
        void invalidNameAndMissingUrl_bothReported() {
            var config = new McpServerConfig("1bad", McpTransport.HTTP,
                null, null, null, null, null, true);
            assertThatThrownBy(() -> McpServerConfig.validate(config))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("invalid server name")
                .hasMessageContaining("url is required");
        }
    }

    // ── record defaults ─────────────────────────────────────

    @Nested
    class RecordDefaults {

        @Test
        void nullArgsDefaultToEmpty() {
            var config = new McpServerConfig("test", McpTransport.HTTP,
                "http://localhost", null, null, null, null, true);
            assertThat(config.args()).isEmpty();
        }

        @Test
        void nullEnvDefaultToEmpty() {
            var config = new McpServerConfig("test", McpTransport.HTTP,
                "http://localhost", null, null, null, null, true);
            assertThat(config.env()).isEmpty();
        }
    }
}
