package com.clawcode.agent.cli.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("FileMcpConfigStore")
class FileMcpConfigStoreTest {

    @TempDir
    Path tempDir;

    private Path configFile;
    private FileMcpConfigStore store;

    @BeforeEach
    void setUp() {
        configFile = tempDir.resolve("mcp-servers.json");
        store = new FileMcpConfigStore(configFile);
    }

    private McpServerConfig httpServer(String name) {
        return new McpServerConfig(name, McpServerConfig.McpTransport.HTTP,
            "http://localhost:8080", null, List.of(), Map.of(), null, true);
    }

    private McpServerConfig stdioServer(String name) {
        return new McpServerConfig(name, McpServerConfig.McpTransport.STDIO,
            null, "node server.js", List.of(), Map.of(), null, true);
    }

    // ── load ───────────────────────────────────────────────────

    @Test
    @DisplayName("load from missing file returns empty list")
    void load_missingFile_returnsEmpty() {
        assertThat(store.load()).isEmpty();
    }

    @Test
    @DisplayName("load from empty JSON object returns empty list")
    void load_emptyObject_returnsEmpty() throws IOException {
        Files.writeString(configFile, "{}");
        assertThat(store.load()).isEmpty();
    }

    @Test
    @DisplayName("load from file with empty servers array returns empty list")
    void load_emptyServersArray_returnsEmpty() throws IOException {
        Files.writeString(configFile, "{\"servers\":[]}");
        assertThat(store.load()).isEmpty();
    }

    // ── add + reload persistence ───────────────────────────────

    @Test
    @DisplayName("add persists and reload returns same config")
    void add_persistsAndReloads() {
        var server = httpServer("test-server");
        store.add(server);

        var reloaded = new FileMcpConfigStore(configFile).load();
        assertThat(reloaded).hasSize(1);
        var loaded = reloaded.get(0);
        assertThat(loaded.name()).isEqualTo("test-server");
        assertThat(loaded.transport()).isEqualTo(McpServerConfig.McpTransport.HTTP);
        assertThat(loaded.url()).isEqualTo("http://localhost:8080");
        assertThat(loaded.enabled()).isTrue();
    }

    @Test
    @DisplayName("add multiple servers preserves insertion order")
    void add_multiple_preservesOrder() {
        store.add(httpServer("alpha"));
        store.add(stdioServer("beta"));
        store.add(httpServer("gamma"));

        var servers = store.load();
        assertThat(servers).satisfiesExactly(
            s -> assertThat(s.name()).isEqualTo("alpha"),
            s -> assertThat(s.name()).isEqualTo("beta"),
            s -> assertThat(s.name()).isEqualTo("gamma")
        );
    }

    // ── duplicate protection ───────────────────────────────────

    @Test
    @DisplayName("add duplicate name throws ValidationException")
    void add_duplicate_throws() {
        store.add(httpServer("my-server"));
        assertThatThrownBy(() -> store.add(httpServer("my-server")))
            .isInstanceOf(McpServerConfig.ValidationException.class)
            .hasMessageContaining("already exists");
    }

    // ── remove ─────────────────────────────────────────────────

    @Test
    @DisplayName("remove existing server returns true and persists removal")
    void remove_existing_returnsTrue() {
        store.add(httpServer("to-remove"));
        store.add(httpServer("keep"));

        assertThat(store.remove("to-remove")).isTrue();

        var remaining = store.load();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).name()).isEqualTo("keep");
    }

    @Test
    @DisplayName("remove non-existing server returns false")
    void remove_nonExisting_returnsFalse() {
        store.add(httpServer("only-one"));
        assertThat(store.remove("no-such-server")).isFalse();
        assertThat(store.load()).hasSize(1);
    }

    @Test
    @DisplayName("remove from empty store returns false")
    void remove_fromEmpty_returnsFalse() {
        assertThat(store.remove("anything")).isFalse();
    }

    // ── find ───────────────────────────────────────────────────

    @Test
    @DisplayName("find existing server returns Optional with config")
    void find_existing_returnsConfig() {
        var server = stdioServer("found-me");
        store.add(server);

        var result = store.find("found-me");
        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("found-me");
        assertThat(result.get().command()).isEqualTo("node server.js");
    }

    @Test
    @DisplayName("find non-existing server returns empty Optional")
    void find_nonExisting_returnsEmpty() {
        store.add(httpServer("alpha"));
        assertThat(store.find("beta")).isEmpty();
    }

    @Test
    @DisplayName("find from empty store returns empty Optional")
    void find_fromEmpty_returnsEmpty() {
        assertThat(store.find("anything")).isEmpty();
    }

    // ── auth token masking ─────────────────────────────────────

    @Test
    @DisplayName("auth token is masked in persisted JSON")
    void authToken_maskedInFile() throws IOException {
        var server = new McpServerConfig("secure", McpServerConfig.McpTransport.HTTP,
            "http://localhost:8080", null, List.of(), Map.of(), "secret-token-123", true);
        store.add(server);

        var content = Files.readString(configFile);
        assertThat(content).contains("***");
        assertThat(content).doesNotContain("secret-token-123");
    }

    // ── deterministic order across save/load cycles ────────────

    @Test
    @DisplayName("order is deterministic across multiple save/load cycles")
    void orderDeterministic_acrossCycles() {
        store.add(httpServer("first"));
        store.add(stdioServer("second"));
        store.add(httpServer("third"));

        var first = store.load();
        store.save(first);
        var reloaded = store.load();

        assertThat(reloaded).satisfiesExactly(
            s -> assertThat(s.name()).isEqualTo("first"),
            s -> assertThat(s.name()).isEqualTo("second"),
            s -> assertThat(s.name()).isEqualTo("third")
        );
    }

    // ── save creates parent directories ────────────────────────

    @Test
    @DisplayName("save creates parent directories if missing")
    void save_createsParentDirs() {
        var nestedConfig = tempDir.resolve("deep").resolve("nested").resolve("mcp.json");
        var nestedStore = new FileMcpConfigStore(nestedConfig);

        nestedStore.add(httpServer("nested-server"));

        assertThat(Files.exists(nestedConfig)).isTrue();
        assertThat(nestedStore.load()).hasSize(1);
    }

    // ── args and env round-trip ────────────────────────────────

    @Test
    @DisplayName("args and env survive save/load round-trip")
    void argsAndEnv_roundTrip() {
        var server = new McpServerConfig("full", McpServerConfig.McpTransport.STDIO,
            null, "node", List.of("--verbose", "--port", "3000"),
            Map.of("API_KEY", "xxx"), null, false);
        store.add(server);

        var loaded = store.find("full").orElseThrow();
        assertThat(loaded.args()).containsExactly("--verbose", "--port", "3000");
        assertThat(loaded.env()).containsEntry("API_KEY", "xxx");
        assertThat(loaded.enabled()).isFalse();
    }

    // ── configPath accessor ────────────────────────────────────

    @Test
    @DisplayName("configPath returns the configured path")
    void configPath_returnsConfigured() {
        assertThat(store.configPath()).isEqualTo(configFile);
    }
}
