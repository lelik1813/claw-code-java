package com.clawcode.agent.plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemPluginRegistryTest {

    @Test
    void parsesPluginsWithTools(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("marketplace.json"), """
            {
              "plugins": [
                {
                  "id": "weather",
                  "name": "Weather Plugin",
                  "version": "1.0.0",
                  "tools": [
                    {
                      "name": "get_weather",
                      "type": "http",
                      "config": {
                        "url": "https://api.weather.com/current",
                        "method": "GET",
                        "timeout": 5000
                      }
                    },
                    {
                      "name": "forecast",
                      "type": "builtin"
                    }
                  ]
                }
              ]
            }
            """);

        var registry = new FileSystemPluginRegistry(
            new PluginsProperties(true, null, List.of(dir.toString())));

        StepVerifier.create(registry.resolve("weather"))
            .assertNext(d -> {
                assertThat(d.id()).isEqualTo("weather");
                assertThat(d.name()).isEqualTo("Weather Plugin");
                assertThat(d.version()).isEqualTo("1.0.0");
                assertThat(d.tools()).hasSize(2);

                PluginToolDescriptor t0 = d.tools().get(0);
                assertThat(t0.name()).isEqualTo("get_weather");
                assertThat(t0.type()).isEqualTo("http");
                assertThat(t0.config()).containsEntry("url", "https://api.weather.com/current");
                assertThat(t0.config()).containsEntry("method", "GET");
                assertThat(t0.config()).containsEntry("timeout", 5000);

                PluginToolDescriptor t1 = d.tools().get(1);
                assertThat(t1.name()).isEqualTo("forecast");
                assertThat(t1.type()).isEqualTo("builtin");
                assertThat(t1.config()).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void pluginWithoutToolsGetsEmptyList(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("marketplace.json"), """
            {
              "plugins": [
                {
                  "id": "legacy",
                  "name": "Legacy Plugin",
                  "version": "0.1.0"
                }
              ]
            }
            """);

        var registry = new FileSystemPluginRegistry(
            new PluginsProperties(true, null, List.of(dir.toString())));

        StepVerifier.create(registry.resolve("legacy"))
            .assertNext(d -> assertThat(d.tools()).isEmpty())
            .verifyComplete();
    }

    @Test
    void toolWithoutTypeDefaultsToBuiltin(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("marketplace.json"), """
            {
              "plugins": [
                {
                  "id": "p",
                  "tools": [
                    { "name": "my_tool" }
                  ]
                }
              ]
            }
            """);

        var registry = new FileSystemPluginRegistry(
            new PluginsProperties(true, null, List.of(dir.toString())));

        StepVerifier.create(registry.resolve("p"))
            .assertNext(d -> {
                assertThat(d.tools()).hasSize(1);
                assertThat(d.tools().get(0).name()).isEqualTo("my_tool");
                assertThat(d.tools().get(0).type()).isEqualTo("builtin");
                assertThat(d.tools().get(0).config()).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void toolWithBlankTypeDefaultsToBuiltin(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("marketplace.json"), """
            {
              "plugins": [
                {
                  "id": "p",
                  "tools": [
                    { "name": "t", "type": "  " }
                  ]
                }
              ]
            }
            """);

        var registry = new FileSystemPluginRegistry(
            new PluginsProperties(true, null, List.of(dir.toString())));

        StepVerifier.create(registry.resolve("p"))
            .assertNext(d -> assertThat(d.tools().get(0).type()).isEqualTo("builtin"))
            .verifyComplete();
    }

    @Test
    void duplicateToolNamesThrow(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("marketplace.json"), """
            {
              "plugins": [
                {
                  "id": "dup",
                  "tools": [
                    { "name": "action" },
                    { "name": "action" }
                  ]
                }
              ]
            }
            """);

        assertThatThrownBy(() -> new FileSystemPluginRegistry(
                new PluginsProperties(true, null, List.of(dir.toString()))))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("duplicate tool name 'action'")
            .hasMessageContaining("dup");
    }

    @Test
    void toolWithBlankNameThrows(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("marketplace.json"), """
            {
              "plugins": [
                {
                  "id": "bad",
                  "tools": [
                    { "name": "" }
                  ]
                }
              ]
            }
            """);

        assertThatThrownBy(() -> new FileSystemPluginRegistry(
                new PluginsProperties(true, null, List.of(dir.toString()))))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("missing or blank 'name'")
            .hasMessageContaining("bad");
    }

    @Test
    void toolWithMissingNameThrows(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("marketplace.json"), """
            {
              "plugins": [
                {
                  "id": "bad",
                  "tools": [
                    { "type": "http" }
                  ]
                }
              ]
            }
            """);

        assertThatThrownBy(() -> new FileSystemPluginRegistry(
                new PluginsProperties(true, null, List.of(dir.toString()))))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("missing or blank 'name'");
    }

    @Test
    void toolWithNonObjectConfigThrows(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("marketplace.json"), """
            {
              "plugins": [
                {
                  "id": "bad",
                  "tools": [
                    { "name": "t", "config": "not-an-object" }
                  ]
                }
              ]
            }
            """);

        assertThatThrownBy(() -> new FileSystemPluginRegistry(
                new PluginsProperties(true, null, List.of(dir.toString()))))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("non-object 'config'")
            .hasMessageContaining("'t'");
    }

    @Test
    void toolWithArrayConfigThrows(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("marketplace.json"), """
            {
              "plugins": [
                {
                  "id": "bad",
                  "tools": [
                    { "name": "t", "config": [1, 2, 3] }
                  ]
                }
              ]
            }
            """);

        assertThatThrownBy(() -> new FileSystemPluginRegistry(
                new PluginsProperties(true, null, List.of(dir.toString()))))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("non-object 'config'");
    }

    @Test
    void duplicatePluginIdThrows(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("marketplace.json"), """
            {
              "plugins": [
                { "id": "x" },
                { "id": "x" }
              ]
            }
            """);

        assertThatThrownBy(() -> new FileSystemPluginRegistry(
                new PluginsProperties(true, null, List.of(dir.toString()))))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Duplicate plugin id 'x'");
    }

    @Test
    void missingPluginIdThrows(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("marketplace.json"), """
            {
              "plugins": [
                { "name": "no-id" }
              ]
            }
            """);

        assertThatThrownBy(() -> new FileSystemPluginRegistry(
                new PluginsProperties(true, null, List.of(dir.toString()))))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Missing required field 'id'");
    }

    @Test
    void invalidJsonThrows(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("marketplace.json"), "not-json");

        assertThatThrownBy(() -> new FileSystemPluginRegistry(
                new PluginsProperties(true, null, List.of(dir.toString()))))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to read plugin marketplace");
    }

    @Test
    void nonExistentDirectoryIsSkipped() {
        var registry = new FileSystemPluginRegistry(
            new PluginsProperties(true, null, List.of("/no/such/directory")));

        StepVerifier.create(registry.list().collectList())
            .assertNext(list -> assertThat(list).isEmpty())
            .verifyComplete();
    }

    @Test
    void listsAllPluginsWithToolCounts(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("marketplace.json"), """
            {
              "plugins": [
                {
                  "id": "alpha",
                  "tools": [{ "name": "a1" }, { "name": "a2" }]
                },
                {
                  "id": "beta",
                  "tools": [{ "name": "b1" }]
                }
              ]
            }
            """);

        var registry = new FileSystemPluginRegistry(
            new PluginsProperties(true, null, List.of(dir.toString())));

        StepVerifier.create(registry.list().collectList())
            .assertNext(list -> {
                assertThat(list).hasSize(2);
                assertThat(list.stream().filter(d -> d.id().equals("alpha")).findFirst().get().tools())
                    .hasSize(2);
                assertThat(list.stream().filter(d -> d.id().equals("beta")).findFirst().get().tools())
                    .hasSize(1);
            })
            .verifyComplete();
    }

    @Test
    void descriptorBackwardCompatConstructorReturnsEmptyTools() {
        var descriptor = new PluginDescriptor(
            "x", "X Plugin", "1.0", Path.of(".").toUri());

        assertThat(descriptor.tools()).isEmpty();
    }

    @Test
    void toolDescriptorNullConfigBecomesEmptyMap() {
        var tool = new PluginToolDescriptor("t", "http", null);
        assertThat(tool.config()).isEmpty();
    }
}
