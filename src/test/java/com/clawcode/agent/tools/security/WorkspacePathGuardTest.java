package com.clawcode.agent.tools.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkspacePathGuardTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir")).normalize();
    private String previousAllowedRoots;

    @BeforeEach
    void clearAllowedRootsProperty() {
        previousAllowedRoots = System.getProperty("app.tools.allowed-roots");
        System.clearProperty("app.tools.allowed-roots");
    }

    @AfterEach
    void restoreAllowedRootsProperty() {
        if (previousAllowedRoots == null) {
            System.clearProperty("app.tools.allowed-roots");
        } else {
            System.setProperty("app.tools.allowed-roots", previousAllowedRoots);
        }
    }

    @Test
    void relativePathResolvesInsideWorkspace() {
        Path result = WorkspacePathGuard.validate("README.md");
        assertThat(result).isEqualTo(ROOT.resolve("README.md").normalize());
        assertThat(result.startsWith(ROOT)).isTrue();
    }

    @Test
    void relativePathInSubdirectoryResolvesInsideWorkspace() {
        Path result = WorkspacePathGuard.validate("src/main/resources/application.properties");
        assertThat(result.startsWith(ROOT)).isTrue();
    }

    @Test
    void absolutePathInsideWorkspaceAccepted() {
        Path result = WorkspacePathGuard.validate(ROOT.resolve("pom.xml").toString());
        assertThat(result.startsWith(ROOT)).isTrue();
    }

    @Test
    void parentTraversalStaysInWorkspace() {
        Path result = WorkspacePathGuard.validate("src/../pom.xml");
        assertThat(result).isEqualTo(ROOT.resolve("pom.xml").normalize());
        assertThat(result.startsWith(ROOT)).isTrue();
    }

    @Test
    void parentTraversalEscapingWorkspaceRejected() {
        assertThatThrownBy(() -> WorkspacePathGuard.validate("../../etc/passwd"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("escapes workspace");
    }

    @Test
    void absolutePathInConfiguredExtraRootAccepted() {
        Path extraRoot = ROOT.getParent().resolve("claw-code-java-test").normalize().toAbsolutePath();
        Path target = extraRoot.resolve("SELF_AUDIT.md").normalize().toAbsolutePath();

        String previous = System.getProperty("app.tools.allowed-roots");
        System.setProperty("app.tools.allowed-roots", extraRoot.toString());
        try {
            Path result = WorkspacePathGuard.validate(target.toString());
            assertThat(result).isEqualTo(target);
        } finally {
            if (previous == null) {
                System.clearProperty("app.tools.allowed-roots");
            } else {
                System.setProperty("app.tools.allowed-roots", previous);
            }
        }
    }

    @Test
    void relativePathUsesConfiguredRootWhenPresent() {
        Path extraRoot = ROOT.getParent().resolve("claw-code-java-test").normalize().toAbsolutePath();
        Path target = extraRoot.resolve("README.md").normalize();

        String previous = System.getProperty("app.tools.allowed-roots");
        System.setProperty("app.tools.allowed-roots", extraRoot.toString());
        try {
            Path result = WorkspacePathGuard.validate("README.md");
            assertThat(result).isEqualTo(target);
        } finally {
            if (previous == null) {
                System.clearProperty("app.tools.allowed-roots");
            } else {
                System.setProperty("app.tools.allowed-roots", previous);
            }
        }
    }

    @Test
    void configuredRootOverridesDefaultWorkspace() {
        Path extraRoot = ROOT.getParent().resolve("claw-code-java-test").normalize().toAbsolutePath();
        Path target = ROOT.resolve("README.md").normalize().toAbsolutePath();

        String previous = System.getProperty("app.tools.allowed-roots");
        System.setProperty("app.tools.allowed-roots", extraRoot.toString());
        try {
            assertThatThrownBy(() -> WorkspacePathGuard.validate(target.toString()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("escapes workspace");
        } finally {
            if (previous == null) {
                System.clearProperty("app.tools.allowed-roots");
            } else {
                System.setProperty("app.tools.allowed-roots", previous);
            }
        }
    }

    @Test
    void nullPathRejected() {
        assertThatThrownBy(() -> WorkspacePathGuard.validate(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("path is required");
    }

    @Test
    void blankPathRejected() {
        assertThatThrownBy(() -> WorkspacePathGuard.validate("  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("path is required");
    }

    @Test
    void effectiveRootsFallsBackToUserDir() {
        String previous = System.getProperty("app.tools.allowed-roots");
        System.clearProperty("app.tools.allowed-roots");
        try {
            var roots = WorkspacePathGuard.effectiveAllowedRoots();
            assertThat(roots).containsExactly(ROOT.toAbsolutePath().normalize());
        } finally {
            if (previous != null) {
                System.setProperty("app.tools.allowed-roots", previous);
            }
        }
    }

    @Test
    void effectiveRootsReturnsConfiguredRoots() {
        Path extraRoot = ROOT.getParent().resolve("claw-code-java-test").normalize().toAbsolutePath();

        String previous = System.getProperty("app.tools.allowed-roots");
        System.setProperty("app.tools.allowed-roots", extraRoot.toString());
        try {
            var roots = WorkspacePathGuard.effectiveAllowedRoots();
            assertThat(roots).containsExactly(extraRoot);
        } finally {
            if (previous == null) {
                System.clearProperty("app.tools.allowed-roots");
            } else {
                System.setProperty("app.tools.allowed-roots", previous);
            }
        }
    }

    @Test
    void effectiveRootsReturnsImmutableList() {
        var roots = WorkspacePathGuard.effectiveAllowedRoots();
        assertThatThrownBy(() -> roots.add(Path.of("x")))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
