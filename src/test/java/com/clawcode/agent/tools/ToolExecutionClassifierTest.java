package com.clawcode.agent.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionClassifierTest {

    @Test
    void fileReadIsReadOnly() {
        assertThat(ToolExecutionClassifier.isReadOnly("file_read")).isTrue();
    }

    @Test
    void fileListIsReadOnly() {
        assertThat(ToolExecutionClassifier.isReadOnly("file_list")).isTrue();
    }

    @Test
    void fileGlobIsReadOnly() {
        assertThat(ToolExecutionClassifier.isReadOnly("file_glob")).isTrue();
    }

    @Test
    void fileSearchIsReadOnly() {
        assertThat(ToolExecutionClassifier.isReadOnly("file_search")).isTrue();
    }

    @Test
    void fileWriteRequiresSerial() {
        assertThat(ToolExecutionClassifier.requiresSerialExecution("file_write")).isTrue();
    }

    @Test
    void fileEditRequiresSerial() {
        assertThat(ToolExecutionClassifier.requiresSerialExecution("file_edit")).isTrue();
    }

    @Test
    void powershellRequiresSerial() {
        assertThat(ToolExecutionClassifier.requiresSerialExecution("powershell")).isTrue();
    }

    @Test
    void webFetchDefaultsToSerial() {
        assertThat(ToolExecutionClassifier.requiresSerialExecution("web_fetch")).isTrue();
    }

    @Test
    void webSearchDefaultsToSerial() {
        assertThat(ToolExecutionClassifier.requiresSerialExecution("web_search")).isTrue();
    }

    @Test
    void unknownToolDefaultsToSerial() {
        assertThat(ToolExecutionClassifier.requiresSerialExecution("unknown_tool")).isTrue();
    }

    @Test
    void unknownToolNotReadOnly() {
        assertThat(ToolExecutionClassifier.isReadOnly("unknown_tool")).isFalse();
    }

    @Test
    void readOnlyToolNotSerial() {
        assertThat(ToolExecutionClassifier.requiresSerialExecution("file_read")).isFalse();
        assertThat(ToolExecutionClassifier.requiresSerialExecution("file_list")).isFalse();
        assertThat(ToolExecutionClassifier.requiresSerialExecution("file_glob")).isFalse();
        assertThat(ToolExecutionClassifier.requiresSerialExecution("file_search")).isFalse();
    }

    @Test
    void serialToolsNotReadOnly() {
        assertThat(ToolExecutionClassifier.isReadOnly("file_write")).isFalse();
        assertThat(ToolExecutionClassifier.isReadOnly("file_edit")).isFalse();
        assertThat(ToolExecutionClassifier.isReadOnly("powershell")).isFalse();
    }

    @Test
    void nullToolNameDefaultsToSerial() {
        assertThat(ToolExecutionClassifier.requiresSerialExecution(null)).isTrue();
        assertThat(ToolExecutionClassifier.isReadOnly(null)).isFalse();
    }
}
