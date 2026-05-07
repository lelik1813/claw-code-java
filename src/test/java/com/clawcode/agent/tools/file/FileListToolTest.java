package com.clawcode.agent.tools.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class FileListToolTest {

    private final FileListTool tool = new FileListTool();
    private String savedRoots;

    @BeforeEach
    void clearAllowedRoots() {
        savedRoots = System.getProperty("app.tools.allowed-roots");
        System.clearProperty("app.tools.allowed-roots");
    }

    @AfterEach
    void restoreAllowedRoots() {
        if (savedRoots != null) {
            System.setProperty("app.tools.allowed-roots", savedRoots);
        } else {
            System.clearProperty("app.tools.allowed-roots");
        }
    }

    @Test
    void definitionDescribesDirectChildListing() {
        var desc = tool.definition().description();
        assertThat(desc)
            .contains("direct children")
            .contains("not recursive")
            .contains("file_read");
    }

    @Test
    void listsDirectDirectoryChildren() throws IOException {
        Path dir = Path.of("target").resolve("file-list-test");
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("README.md"), "hello");

        StepVerifier.create(tool.execute(Map.of("path", dir.toString()), null))
            .assertNext(result -> {
                assertThat(result).isInstanceOf(List.class);
                @SuppressWarnings("unchecked")
                var entries = (List<Map<String, Object>>) result;
                assertThat(entries)
                    .extracting(entry -> entry.get("name"))
                    .containsExactly("README.md", "src");
                assertThat(entries)
                    .extracting(entry -> entry.get("type"))
                    .containsExactly("file", "directory");
            })
            .verifyComplete();
    }

    @Test
    void acceptsStringInput() throws IOException {
        Path dir = Path.of("target").resolve("file-list-string");
        Files.createDirectories(dir);

        StepVerifier.create(tool.execute(dir.toString(), null))
            .assertNext(result -> assertThat(result).isInstanceOf(List.class))
            .verifyComplete();
    }

    @Test
    void fileReturnsHelpfulError() throws IOException {
        Path file = Path.of("target").resolve("file-list-file.txt");
        Files.writeString(file, "hello");

        StepVerifier.create(tool.execute(Map.of("path", file.toString()), null))
            .expectErrorMatches(e ->
                e instanceof IllegalArgumentException
                && e.getMessage().contains("use file_read instead"))
            .verify();
    }

    @Test
    void missingDirectoryReturnsError() {
        String missing = Path.of("target").resolve("missing-dir").toString();

        StepVerifier.create(tool.execute(Map.of("path", missing), null))
            .expectErrorMatches(e ->
                e.getMessage() != null
                && e.getMessage().contains("missing-dir"))
            .verify();
    }
}
