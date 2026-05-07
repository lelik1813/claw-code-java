package com.clawcode.agent.tools.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class WebUrlGuardTest {

    private final WebUrlGuard guard = new WebUrlGuard(defaultProps());

    @Test
    void validHttpsAccepted() {
        var uri = guard.validateAndNormalize("https://example.com/path?q=1");
        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("example.com");
    }

    @Test
    void validHttpAccepted() {
        var uri = guard.validateAndNormalize("http://example.com/");
        assertThat(uri.getScheme()).isEqualTo("http");
    }

    @Test
    void fileSchemeRejected() {
        assertThatThrownBy(() -> guard.validateAndNormalize("file:///etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Scheme not allowed");
    }

    @Test
    void ftpSchemeRejected() {
        assertThatThrownBy(() -> guard.validateAndNormalize("ftp://files.example.com/data"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Scheme not allowed");
    }

    @Test
    void noSchemeRejected() {
        assertThatThrownBy(() -> guard.validateAndNormalize("example.com/page"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noHostRejected() {
        assertThatThrownBy(() -> guard.validateAndNormalize("https:///path-only"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no host");
    }

    @Test
    void localhostRejected() {
        assertThatThrownBy(() -> guard.validateAndNormalize("https://localhost:8080/api"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Private/local");
    }

    @Test
    void loopbackIpRejected() {
        assertThatThrownBy(() -> guard.validateAndNormalize("https://127.0.0.1/api"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Private/local");
    }

    @Test
    void privateClassARejected() {
        assertThatThrownBy(() -> guard.validateAndNormalize("https://10.0.0.1/secret"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Private/local");
    }

    @Test
    void privateClassBRejected() {
        assertThatThrownBy(() -> guard.validateAndNormalize("https://172.16.0.1/secret"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Private/local");
    }

    @Test
    void privateClassCRejected() {
        assertThatThrownBy(() -> guard.validateAndNormalize("https://192.168.1.1/secret"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Private/local");
    }

    @Test
    void linkLocalRejected() {
        assertThatThrownBy(() -> guard.validateAndNormalize("https://169.254.169.254/metadata"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Private/local");
    }

    @Test
    void blockedHostRejected() {
        WebUrlGuard withBlocklist = new WebUrlGuard(
            new WebToolsProperties(true, true, true, null, null, 30_000,
                1_048_576, 50_000, List.of("http", "https"),
                List.of("evil.com", "malware.net")));

        assertThatThrownBy(() -> withBlocklist.validateAndNormalize("https://evil.com/payload"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Host blocked");
    }

    @Test
    void blockedHostCaseInsensitive() {
        WebUrlGuard withBlocklist = new WebUrlGuard(
            new WebToolsProperties(true, true, true, null, null, 30_000,
                1_048_576, 50_000, List.of("http", "https"),
                List.of("Evil.COM")));

        assertThatThrownBy(() -> withBlocklist.validateAndNormalize("https://evil.com/payload"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Host blocked");
    }

    @Test
    void customSchemeAllowedWhenConfigured() {
        WebUrlGuard wsGuard = new WebUrlGuard(
            new WebToolsProperties(true, true, true, null, null, 30_000,
                1_048_576, 50_000, List.of("wss", "https"), List.of()));

        var uri = wsGuard.validateAndNormalize("wss://ws.example.com/socket");
        assertThat(uri.getScheme()).isEqualTo("wss");
    }

    @Test
    void urlNormalized() {
        var uri = guard.validateAndNormalize("https://example.com/a/../b/./c");
        assertThat(uri.getPath()).isEqualTo("/b/c");
    }

    private static WebToolsProperties defaultProps() {
        return new WebToolsProperties(true, true, true, null, null,
            30_000, 1_048_576, 50_000, List.of("http", "https"), List.of());
    }
}
