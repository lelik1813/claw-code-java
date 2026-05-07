package com.clawcode.agent.cli;

import java.util.List;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.clawcode.agent.cli.AgentApiDtos.PluginEntry;
import com.clawcode.agent.cli.AgentApiDtos.PluginManifest;
import com.clawcode.agent.cli.AgentApiDtos.SubmitMessageRequest;
import com.clawcode.agent.cli.AgentApiDtos.SubmitTaskRequest;
import com.clawcode.agent.cli.AgentApiDtos.TaskResult;
import com.clawcode.agent.cli.AgentApiDtos.TaskStatus;
import com.clawcode.agent.cli.AgentApiDtos.TaskSubmitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentApiDtosTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);
    }

    // ── SubmitMessageRequest ───────────────────────────────────

    @Nested
    class SubmitMessageRequestTests {

        @Test
        void roundTrip_withSkillIds() throws Exception {
            var req = new SubmitMessageRequest("hello", List.of("translator", "summarizer"));
            String json = mapper.writeValueAsString(req);
            var roundTripped = mapper.readValue(json, SubmitMessageRequest.class);

            assertThat(roundTripped.content()).isEqualTo("hello");
            assertThat(roundTripped.skillIds()).containsExactly("translator", "summarizer");
        }

        @Test
        void serialize_nullSkillIds_omitted() throws Exception {
            var req = new SubmitMessageRequest("hello", null);
            String json = mapper.writeValueAsString(req);
            JsonNode tree = mapper.readTree(json);

            assertThat(tree.has("content")).isTrue();
            assertThat(tree.has("skillIds")).isFalse();
        }

        @Test
        void deserialize_withoutSkillIds() throws Exception {
            var req = mapper.readValue("{\"content\":\"test\"}", SubmitMessageRequest.class);
            assertThat(req.content()).isEqualTo("test");
            assertThat(req.skillIds()).isNull();
        }

        @Test
        void blankContent_rejected() {
            assertThatThrownBy(() -> new SubmitMessageRequest("  ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content must not be blank");
        }

        @Test
        void nullContent_rejected() {
            assertThatThrownBy(() -> new SubmitMessageRequest(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── SubmitTaskRequest ──────────────────────────────────────

    @Nested
    class SubmitTaskRequestTests {

        @Test
        void roundTrip() throws Exception {
            var req = new SubmitTaskRequest("s-1", "t-1", "shell", "ls -la");
            String json = mapper.writeValueAsString(req);
            var roundTripped = mapper.readValue(json, SubmitTaskRequest.class);

            assertThat(roundTripped.sessionId()).isEqualTo("s-1");
            assertThat(roundTripped.turnId()).isEqualTo("t-1");
            assertThat(roundTripped.taskType()).isEqualTo("shell");
            assertThat(roundTripped.input()).isEqualTo("ls -la");
        }

        @Test
        void nullSessionId_rejected() {
            assertThatThrownBy(() -> new SubmitTaskRequest(null, null, null, "cmd"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void blankInput_rejected() {
            assertThatThrownBy(() -> new SubmitTaskRequest("s-1", null, null, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void nullableFields_accepted() {
            var req = new SubmitTaskRequest("s-1", null, null, "cmd");
            assertThat(req.sessionId()).isEqualTo("s-1");
            assertThat(req.turnId()).isNull();
            assertThat(req.taskType()).isNull();
        }
    }

    // ── TaskSubmitResult ───────────────────────────────────────

    @Nested
    class TaskSubmitResultTests {

        @Test
        void roundTrip() throws Exception {
            var result = new TaskSubmitResult("task-1", "accepted", "2026-04-24T12:00:00Z");
            String json = mapper.writeValueAsString(result);
            var roundTripped = mapper.readValue(json, TaskSubmitResult.class);

            assertThat(roundTripped.taskId()).isEqualTo("task-1");
            assertThat(roundTripped.status()).isEqualTo("accepted");
        }

        @Test
        void tolerantParsing_ignoresUnknownFields() throws Exception {
            var json = "{\"taskId\":\"t-1\",\"status\":\"pending\",\"futureField\":42}";
            var result = mapper.readValue(json, TaskSubmitResult.class);
            assertThat(result.taskId()).isEqualTo("t-1");
        }
    }

    // ── TaskStatus ─────────────────────────────────────────────

    @Nested
    class TaskStatusTests {

        @Test
        void roundTrip() throws Exception {
            var status = new TaskStatus("task-1", "running", "2026-04-24T12:01:00Z", null);
            String json = mapper.writeValueAsString(status);
            var roundTripped = mapper.readValue(json, TaskStatus.class);

            assertThat(roundTripped.taskId()).isEqualTo("task-1");
            assertThat(roundTripped.status()).isEqualTo("running");
            assertThat(roundTripped.error()).isNull();
        }
    }

    // ── TaskResult ─────────────────────────────────────────────

    @Nested
    class TaskResultTests {

        @Test
        void roundTrip_withOutput() throws Exception {
            var result = new TaskResult("task-1", "completed", "file contents", null);
            String json = mapper.writeValueAsString(result);
            var roundTripped = mapper.readValue(json, TaskResult.class);

            assertThat(roundTripped.output()).isEqualTo("file contents");
            assertThat(roundTripped.error()).isNull();
        }

        @Test
        void roundTrip_withError() throws Exception {
            var result = new TaskResult("task-2", "failed", null, "command timed out");
            String json = mapper.writeValueAsString(result);
            var roundTripped = mapper.readValue(json, TaskResult.class);

            assertThat(roundTripped.status()).isEqualTo("failed");
            assertThat(roundTripped.error()).isEqualTo("command timed out");
        }
    }

    // ── PluginManifest ─────────────────────────────────────────

    @Nested
    class PluginManifestTests {

        @Test
        void roundTrip() throws Exception {
            var manifest = new PluginManifest("my-plugin", "my-plugin-v1", "1.0.0");
            String json = mapper.writeValueAsString(manifest);
            var roundTripped = mapper.readValue(json, PluginManifest.class);

            assertThat(roundTripped.name()).isEqualTo("my-plugin");
            assertThat(roundTripped.id()).isEqualTo("my-plugin-v1");
            assertThat(roundTripped.version()).isEqualTo("1.0.0");
        }

        @Test
        void nullVersion_accepted() throws Exception {
            var json = "{\"name\":\"test\",\"id\":\"test-v1\"}";
            var manifest = mapper.readValue(json, PluginManifest.class);
            assertThat(manifest.version()).isNull();
        }

        @Test
        void tolerantParsing_ignoresExtraFields() throws Exception {
            var json = "{\"name\":\"test\",\"id\":\"test-v1\",\"description\":\"A plugin\",\"author\":\"dev\"}";
            var manifest = mapper.readValue(json, PluginManifest.class);
            assertThat(manifest.name()).isEqualTo("test");
        }
    }

    // ── PluginEntry ────────────────────────────────────────────

    @Nested
    class PluginEntryTests {

        @Test
        void roundTrip_allFields() throws Exception {
            var entry = new PluginEntry("my-plugin", "my-plugin-v1", "PATH",
                "1.0.0", true, "2026-04-24T12:00:00Z", "/path/to/plugin");
            String json = mapper.writeValueAsString(entry);
            var roundTripped = mapper.readValue(json, PluginEntry.class);

            assertThat(roundTripped.name()).isEqualTo("my-plugin");
            assertThat(roundTripped.enabled()).isTrue();
            assertThat(roundTripped.source()).isEqualTo("PATH");
        }

        @Test
        void nullFields_omittedFromJson() throws Exception {
            var entry = new PluginEntry("test", "test-v1", "URL",
                null, true, null, null);
            String json = mapper.writeValueAsString(entry);
            JsonNode tree = mapper.readTree(json);

            assertThat(tree.has("version")).isFalse();
            assertThat(tree.has("installedAt")).isFalse();
            assertThat(tree.has("pathOrUrl")).isFalse();
            assertThat(tree.has("name")).isTrue();
        }
    }
}
