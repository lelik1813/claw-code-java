package com.clawcode.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.clawcode.agent.tools.file.FileEditTool;
import com.clawcode.agent.tools.file.FileGlobTool;
import com.clawcode.agent.tools.file.FileListTool;
import com.clawcode.agent.tools.file.FileReadTool;
import com.clawcode.agent.tools.file.FileReadStateStore;
import com.clawcode.agent.tools.file.FileSearchTool;
import com.clawcode.agent.tools.file.FileWriteTool;
import com.clawcode.agent.tools.shell.PowerShellTool;
import com.clawcode.agent.tools.shell.PowerShellToolProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolDescriptionParityTest {

    @Test
    void fileReadGuidance() {
        var def = new FileReadTool(new com.clawcode.agent.tools.file.FileReadStateStore()).definition();
        assertThat(def.description())
            .contains("read-only")
            .containsIgnoringCase("targeted")
            .contains("not directories")
            .contains("file_list")
            .contains("file_glob")
            .contains("file_search");
        assertThat(pathDescription(def, "path"))
            .contains("read-only")
            .containsIgnoringCase("targeted");
    }

    @Test
    void fileListGuidance() {
        var desc = new FileListTool().definition().description();
        assertThat(desc)
            .contains("direct children")
            .contains("not recursive")
            .contains("file_read");
    }

    @Test
    void fileGlobGuidance() {
        var desc = new FileGlobTool().definition().description();
        assertThat(desc)
            .contains("glob")
            .contains("bounded")
            .contains("before reading multiple files")
            .contains("file_read");
    }

    @Test
    void fileSearchGuidance() {
        var desc = new FileSearchTool().definition().description();
        assertThat(desc)
            .contains("content search")
            .contains("instead of shell")
            .contains("bounded")
            .contains("limit");
    }

    @Test
    void fileWriteGuidance() {
        var desc = new FileWriteTool(new FileReadStateStore()).definition().description();
        assertThat(desc)
            .contains("full overwrite")
            .contains("read first")
            .contains("existing file")
            .containsIgnoringCase("do not create docs unless explicitly requested");
    }

    @Test
    void fileEditGuidance() {
        var def = new FileEditTool(new FileReadStateStore()).definition();
        assertThat(def.description())
            .contains("targeted edit")
            .contains("existing file")
            .contains("file_read")
            .contains("exactly one occurrence")
            .containsIgnoringCase("not for creating new files");
        assertThat(pathDescription(def, "old_text"))
            .containsIgnoringCase("exact text")
            .contains("exactly once");
        assertThat(pathDescription(def, "new_text"))
            .containsIgnoringCase("replacement text");
    }

    @Test
    void powershellGuidance() {
        var desc = new PowerShellTool(new PowerShellToolProperties(30)).definition().description();
        assertThat(desc)
            .contains("build")
            .contains("git")
            .contains("system commands")
            .containsIgnoringCase("do not use for file read/search/write");
    }

    @SuppressWarnings("unchecked")
    private static String pathDescription(ToolDefinition def, String property) {
        var props = (Map<String, Object>) def.inputSchema().get("properties");
        var propSchema = (Map<String, Object>) props.get(property);
        return (String) propSchema.get("description");
    }
}
