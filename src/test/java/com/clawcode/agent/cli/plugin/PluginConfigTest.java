package com.clawcode.agent.cli.plugin;

import java.time.Instant;
import com.clawcode.agent.cli.plugin.PluginConfig.PluginSource;
import com.clawcode.agent.cli.plugin.PluginConfig.ValidationException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class PluginConfigTest {

    // ── source parsing ──────────────────────────────────────

    @Nested
    class SourceParsing {

        @Test
        void pathParsed() {
            assertThat(PluginSource.parse("PATH")).isEqualTo(PluginSource.PATH);
        }

        @Test
        void urlParsed() {
            assertThat(PluginSource.parse("URL")).isEqualTo(PluginSource.URL);
        }

        @Test
        void registryParsed() {
            assertThat(PluginSource.parse("REGISTRY")).isEqualTo(PluginSource.REGISTRY);
        }

        @Test
        void caseInsensitive() {
            assertThat(PluginSource.parse("path")).isEqualTo(PluginSource.PATH);
            assertThat(PluginSource.parse("url")).isEqualTo(PluginSource.URL);
            assertThat(PluginSource.parse("registry")).isEqualTo(PluginSource.REGISTRY);
        }

        @Test
        void invalidThrows() {
            assertThatThrownBy(() -> PluginSource.parse("BOGUS"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("invalid source");
        }

        @Test
        void nullThrows() {
            assertThatThrownBy(() -> PluginSource.parse(null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("source is required");
        }
    }

    // ── name validation ─────────────────────────────────────

    @Nested
    class NameValidation {

        @Test
        void validNames() {
            assertThatCode(() -> PluginConfig.validateName("my-plugin")).doesNotThrowAnyException();
            assertThatCode(() -> PluginConfig.validateName("Plugin1")).doesNotThrowAnyException();
            assertThatCode(() -> PluginConfig.validateName("a")).doesNotThrowAnyException();
            assertThatCode(() -> PluginConfig.validateName("abc_def-123")).doesNotThrowAnyException();
            assertThatCode(() -> PluginConfig.validateName("com.example.my-plugin")).doesNotThrowAnyException();
        }

        @Test
        void nullRejected() {
            assertThatThrownBy(() -> PluginConfig.validateName(null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("plugin name must not be blank");
        }

        @Test
        void blankRejected() {
            assertThatThrownBy(() -> PluginConfig.validateName("   "))
                .isInstanceOf(ValidationException.class)
                .hasMessage("plugin name must not be blank");
        }

        @Test
        void startsWithDigitRejected() {
            assertThatThrownBy(() -> PluginConfig.validateName("1plugin"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("invalid plugin name");
        }

        @Test
        void specialCharsRejected() {
            assertThatThrownBy(() -> PluginConfig.validateName("my@plugin"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("invalid plugin name");
        }

        @Test
        void dotSegmentStartingWithDigitRejected() {
            assertThatThrownBy(() -> PluginConfig.validateName("com.1bad"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("invalid plugin name");
        }
    }

    // ── id validation ───────────────────────────────────────

    @Nested
    class IdValidation {

        @Test
        void validIds() {
            assertThatCode(() -> PluginConfig.validateId("abc123")).doesNotThrowAnyException();
            assertThatCode(() -> PluginConfig.validateId("com.example.plugin-v2")).doesNotThrowAnyException();
            assertThatCode(() -> PluginConfig.validateId("1startsWithDigit")).doesNotThrowAnyException();
        }

        @Test
        void nullRejected() {
            assertThatThrownBy(() -> PluginConfig.validateId(null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("plugin id must not be blank");
        }

        @Test
        void blankRejected() {
            assertThatThrownBy(() -> PluginConfig.validateId("   "))
                .isInstanceOf(ValidationException.class)
                .hasMessage("plugin id must not be blank");
        }

        @Test
        void specialCharsRejected() {
            assertThatThrownBy(() -> PluginConfig.validateId("id@bad"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("invalid plugin id");
        }

        @Test
        void startsWithSpecialCharRejected() {
            assertThatThrownBy(() -> PluginConfig.validateId("_underscore"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("invalid plugin id");
        }
    }

    // ── full config validation ──────────────────────────────

    @Nested
    class ConfigValidation {

        @Test
        void validPathConfig() {
            var config = new PluginConfig("my-plugin", "my-plugin-v1", PluginSource.PATH,
                "1.0.0", true, Instant.now(), "/opt/plugins/my-plugin.jar");
            assertThatCode(() -> PluginConfig.validate(config)).doesNotThrowAnyException();
        }

        @Test
        void validUrlConfig() {
            var config = new PluginConfig("my-plugin", "my-plugin-v1", PluginSource.URL,
                "2.0.0", true, Instant.now(), "https://registry.example.com/plugins/my-plugin.jar");
            assertThatCode(() -> PluginConfig.validate(config)).doesNotThrowAnyException();
        }

        @Test
        void validRegistryConfig() {
            var config = new PluginConfig("my-plugin", "my-plugin-v1", PluginSource.REGISTRY,
                "1.0.0", true, Instant.now(), null);
            assertThatCode(() -> PluginConfig.validate(config)).doesNotThrowAnyException();
        }

        @Test
        void pathMissingPathOrUrl() {
            var config = new PluginConfig("my-plugin", "my-plugin-v1", PluginSource.PATH,
                "1.0.0", true, Instant.now(), null);
            assertThatThrownBy(() -> PluginConfig.validate(config))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("pathOrUrl is required for PATH source");
        }

        @Test
        void urlMissingPathOrUrl() {
            var config = new PluginConfig("my-plugin", "my-plugin-v1", PluginSource.URL,
                "1.0.0", true, Instant.now(), "   ");
            assertThatThrownBy(() -> PluginConfig.validate(config))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("pathOrUrl is required for URL source");
        }

        @Test
        void nullSource() {
            var config = new PluginConfig("my-plugin", "my-plugin-v1", null,
                "1.0.0", true, Instant.now(), null);
            assertThatThrownBy(() -> PluginConfig.validate(config))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("source is required");
        }

        @Test
        void blankVersionRejected() {
            var config = new PluginConfig("my-plugin", "my-plugin-v1", PluginSource.REGISTRY,
                "   ", true, Instant.now(), null);
            assertThatThrownBy(() -> PluginConfig.validate(config))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("version must not be blank if provided");
        }

        @Test
        void nullVersionAccepted() {
            var config = new PluginConfig("my-plugin", "my-plugin-v1", PluginSource.REGISTRY,
                null, true, Instant.now(), null);
            assertThatCode(() -> PluginConfig.validate(config)).doesNotThrowAnyException();
        }

        @Test
        void multipleErrors_aggregated() {
            var config = new PluginConfig("1bad", "", null,
                "   ", true, Instant.now(), null);
            assertThatThrownBy(() -> PluginConfig.validate(config))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("invalid plugin name")
                .hasMessageContaining("plugin id must not be blank")
                .hasMessageContaining("source is required")
                .hasMessageContaining("version must not be blank");
        }

        @Test
        void invalidNameAndMissingPathOrUrl_bothReported() {
            var config = new PluginConfig("@bad", "valid-id", PluginSource.PATH,
                "1.0.0", true, Instant.now(), null);
            assertThatThrownBy(() -> PluginConfig.validate(config))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("invalid plugin name")
                .hasMessageContaining("pathOrUrl is required for PATH source");
        }
    }
}
