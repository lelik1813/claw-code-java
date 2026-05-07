package com.clawcode.agent.cli.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("FilePluginConfigStore")
class FilePluginConfigStoreTest {

    @TempDir
    Path tempDir;

    private Path configFile;
    private FilePluginConfigStore store;

    @BeforeEach
    void setUp() {
        configFile = tempDir.resolve("plugins.json");
        store = new FilePluginConfigStore(configFile);
    }

    private PluginConfig pathPlugin(String name, String id) {
        return new PluginConfig(name, id, PluginConfig.PluginSource.PATH,
            "1.0.0", true, Instant.parse("2026-01-15T10:30:00Z"), "/opt/plugins/" + name + ".jar");
    }

    private PluginConfig urlPlugin(String name, String id) {
        return new PluginConfig(name, id, PluginConfig.PluginSource.URL,
            "2.0.0", true, Instant.parse("2026-02-20T14:00:00Z"),
            "https://registry.example.com/plugins/" + name + ".jar");
    }

    private PluginConfig registryPlugin(String name, String id) {
        return new PluginConfig(name, id, PluginConfig.PluginSource.REGISTRY,
            "1.0.0", true, Instant.parse("2026-03-10T08:00:00Z"), null);
    }

    // ── load ────────────────────────────────────────────────

    @Nested
    class Load {

        @Test
        @DisplayName("load from missing file returns empty list")
        void missingFile_returnsEmpty() {
            assertThat(store.load()).isEmpty();
        }

        @Test
        @DisplayName("load from empty JSON object returns empty list")
        void emptyObject_returnsEmpty() throws IOException {
            Files.writeString(configFile, "{}");
            assertThat(store.load()).isEmpty();
        }

        @Test
        @DisplayName("load from file with empty plugins array returns empty list")
        void emptyArray_returnsEmpty() throws IOException {
            Files.writeString(configFile, "{\"plugins\":[]}");
            assertThat(store.load()).isEmpty();
        }
    }

    // ── add + reload persistence ────────────────────────────

    @Nested
    class AddAndPersist {

        @Test
        @DisplayName("add persists and reload returns same config")
        void add_persistsAndReloads() {
            var plugin = pathPlugin("my-plugin", "my-plugin-v1");
            store.add(plugin);

            var reloaded = new FilePluginConfigStore(configFile).load();
            assertThat(reloaded).hasSize(1);
            var loaded = reloaded.get(0);
            assertThat(loaded.name()).isEqualTo("my-plugin");
            assertThat(loaded.id()).isEqualTo("my-plugin-v1");
            assertThat(loaded.source()).isEqualTo(PluginConfig.PluginSource.PATH);
            assertThat(loaded.version()).isEqualTo("1.0.0");
            assertThat(loaded.enabled()).isTrue();
            assertThat(loaded.installedAt()).isEqualTo(Instant.parse("2026-01-15T10:30:00Z"));
            assertThat(loaded.pathOrUrl()).isEqualTo("/opt/plugins/my-plugin.jar");
        }

        @Test
        @DisplayName("add multiple plugins preserves insertion order")
        void add_multiple_preservesOrder() {
            store.add(pathPlugin("alpha", "alpha-v1"));
            store.add(urlPlugin("beta", "beta-v2"));
            store.add(registryPlugin("gamma", "gamma-v3"));

            var plugins = store.load();
            assertThat(plugins).satisfiesExactly(
                p -> assertThat(p.name()).isEqualTo("alpha"),
                p -> assertThat(p.name()).isEqualTo("beta"),
                p -> assertThat(p.name()).isEqualTo("gamma")
            );
        }

        @Test
        @DisplayName("state survives restart via new store instance")
        void survivesRestart() {
            store.add(pathPlugin("persist-me", "persist-v1"));
            store.add(urlPlugin("also-persist", "also-v1"));

            var restarted = new FilePluginConfigStore(configFile);
            var plugins = restarted.load();
            assertThat(plugins).hasSize(2);
            assertThat(plugins.get(0).name()).isEqualTo("persist-me");
            assertThat(plugins.get(1).name()).isEqualTo("also-persist");
        }
    }

    // ── duplicate protection ────────────────────────────────

    @Nested
    class DuplicateProtection {

        @Test
        @DisplayName("add duplicate name throws ValidationException")
        void duplicateName_throws() {
            store.add(pathPlugin("dup-name", "id-1"));
            assertThatThrownBy(() -> store.add(urlPlugin("dup-name", "id-2")))
                .isInstanceOf(PluginConfig.ValidationException.class)
                .hasMessageContaining("already exists")
                .hasMessageContaining("dup-name");
        }

        @Test
        @DisplayName("add duplicate id throws ValidationException")
        void duplicateId_throws() {
            store.add(pathPlugin("name-1", "dup-id"));
            assertThatThrownBy(() -> store.add(urlPlugin("name-2", "dup-id")))
                .isInstanceOf(PluginConfig.ValidationException.class)
                .hasMessageContaining("already exists")
                .hasMessageContaining("dup-id");
        }
    }

    // ── remove ──────────────────────────────────────────────

    @Nested
    class Remove {

        @Test
        @DisplayName("remove existing plugin returns true and persists removal")
        void removeExisting_returnsTrue() {
            store.add(pathPlugin("to-remove", "rm-v1"));
            store.add(registryPlugin("keep", "keep-v1"));

            assertThat(store.remove("to-remove")).isTrue();

            var remaining = store.load();
            assertThat(remaining).hasSize(1);
            assertThat(remaining.get(0).name()).isEqualTo("keep");
        }

        @Test
        @DisplayName("remove non-existing plugin returns false")
        void removeNonExisting_returnsFalse() {
            store.add(pathPlugin("only-one", "only-v1"));
            assertThat(store.remove("no-such-plugin")).isFalse();
            assertThat(store.load()).hasSize(1);
        }

        @Test
        @DisplayName("remove from empty store returns false")
        void removeFromEmpty_returnsFalse() {
            assertThat(store.remove("anything")).isFalse();
        }
    }

    // ── find ────────────────────────────────────────────────

    @Nested
    class Find {

        @Test
        @DisplayName("find existing plugin returns Optional with config")
        void findExisting_returnsConfig() {
            var plugin = urlPlugin("found-me", "found-v1");
            store.add(plugin);

            var result = store.find("found-me");
            assertThat(result).isPresent();
            assertThat(result.get().name()).isEqualTo("found-me");
            assertThat(result.get().id()).isEqualTo("found-v1");
            assertThat(result.get().source()).isEqualTo(PluginConfig.PluginSource.URL);
        }

        @Test
        @DisplayName("find non-existing plugin returns empty Optional")
        void findNonExisting_returnsEmpty() {
            store.add(pathPlugin("alpha", "alpha-v1"));
            assertThat(store.find("beta")).isEmpty();
        }

        @Test
        @DisplayName("find from empty store returns empty Optional")
        void findFromEmpty_returnsEmpty() {
            assertThat(store.find("anything")).isEmpty();
        }
    }

    // ── updateEnabled ───────────────────────────────────────

    @Nested
    class UpdateEnabled {

        @Test
        @DisplayName("enable a disabled plugin")
        void enableDisabled() {
            var plugin = new PluginConfig("toggle", "toggle-v1", PluginConfig.PluginSource.PATH,
                "1.0.0", false, Instant.parse("2026-01-01T00:00:00Z"), "/opt/toggle.jar");
            store.add(plugin);
            assertThat(store.find("toggle").get().enabled()).isFalse();

            var updated = store.updateEnabled("toggle", true);
            assertThat(updated.enabled()).isTrue();

            var reloaded = new FilePluginConfigStore(configFile).load();
            assertThat(reloaded.get(0).enabled()).isTrue();
        }

        @Test
        @DisplayName("disable an enabled plugin")
        void disableEnabled() {
            store.add(pathPlugin("shut-off", "shut-v1"));
            assertThat(store.find("shut-off").get().enabled()).isTrue();

            var updated = store.updateEnabled("shut-off", false);
            assertThat(updated.enabled()).isFalse();

            var reloaded = new FilePluginConfigStore(configFile).load();
            assertThat(reloaded.get(0).enabled()).isFalse();
        }

        @Test
        @DisplayName("enable/disable survives restart")
        void enableDisable_survivesRestart() {
            store.add(pathPlugin("cycle", "cycle-v1"));
            store.updateEnabled("cycle", false);

            var restarted = new FilePluginConfigStore(configFile);
            assertThat(restarted.find("cycle").get().enabled()).isFalse();

            restarted.updateEnabled("cycle", true);
            var finalCheck = new FilePluginConfigStore(configFile);
            assertThat(finalCheck.find("cycle").get().enabled()).isTrue();
        }

        @Test
        @DisplayName("updateEnabled on non-existing plugin throws")
        void notFound_throws() {
            assertThatThrownBy(() -> store.updateEnabled("ghost", true))
                .isInstanceOf(PluginConfig.ValidationException.class)
                .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("updateEnabled preserves other fields")
        void preservesOtherFields() {
            store.add(urlPlugin("preserve", "preserve-v1"));
            var updated = store.updateEnabled("preserve", false);

            assertThat(updated.name()).isEqualTo("preserve");
            assertThat(updated.id()).isEqualTo("preserve-v1");
            assertThat(updated.source()).isEqualTo(PluginConfig.PluginSource.URL);
            assertThat(updated.version()).isEqualTo("2.0.0");
            assertThat(updated.pathOrUrl()).isEqualTo("https://registry.example.com/plugins/preserve.jar");
        }
    }

    // ── deterministic order ─────────────────────────────────

    @Test
    @DisplayName("order is deterministic across multiple save/load cycles")
    void orderDeterministic_acrossCycles() {
        store.add(pathPlugin("first", "first-v1"));
        store.add(urlPlugin("second", "second-v2"));
        store.add(registryPlugin("third", "third-v3"));

        var loaded = store.load();
        store.save(loaded);
        var reloaded = store.load();

        assertThat(reloaded).satisfiesExactly(
            p -> assertThat(p.name()).isEqualTo("first"),
            p -> assertThat(p.name()).isEqualTo("second"),
            p -> assertThat(p.name()).isEqualTo("third")
        );
    }

    // ── save creates parent directories ─────────────────────

    @Test
    @DisplayName("save creates parent directories if missing")
    void save_createsParentDirs() {
        var nestedConfig = tempDir.resolve("deep").resolve("nested").resolve("plugins.json");
        var nestedStore = new FilePluginConfigStore(nestedConfig);

        nestedStore.add(pathPlugin("nested-plugin", "nested-v1"));

        assertThat(Files.exists(nestedConfig)).isTrue();
        assertThat(nestedStore.load()).hasSize(1);
    }

    // ── configPath accessor ─────────────────────────────────

    @Test
    @DisplayName("configPath returns the configured path")
    void configPath_returnsConfigured() {
        assertThat(store.configPath()).isEqualTo(configFile);
    }

    // ── full lifecycle ──────────────────────────────────────

    @Nested
    class FullLifecycle {

        @Test
        @DisplayName("add list enable disable remove survives restart")
        void fullCycle() {
            store.add(pathPlugin("lc-a", "lc-a-v1"));
            store.add(urlPlugin("lc-b", "lc-b-v1"));

            store.updateEnabled("lc-a", false);
            store.remove("lc-b");

            var restarted = new FilePluginConfigStore(configFile);
            var plugins = restarted.load();
            assertThat(plugins).hasSize(1);
            assertThat(plugins.get(0).name()).isEqualTo("lc-a");
            assertThat(plugins.get(0).enabled()).isFalse();

            restarted.updateEnabled("lc-a", true);
            restarted.add(registryPlugin("lc-c", "lc-c-v1"));

            var finalCheck = new FilePluginConfigStore(configFile).load();
            assertThat(finalCheck).hasSize(2);
            assertThat(finalCheck.get(0).enabled()).isTrue();
            assertThat(finalCheck.get(1).name()).isEqualTo("lc-c");
        }
    }
}
