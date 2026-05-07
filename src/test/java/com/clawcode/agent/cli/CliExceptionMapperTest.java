package com.clawcode.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class CliExceptionMapperTest {

    @Test
    void authException_mapsToExitAuth() {
        var exit = CliExceptionMapper.map(new CliAuthException("bad key"));
        assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_AUTH);
        assertThat(exit.message()).contains("Authentication failed");
        assertThat(exit.message()).contains("bad key");
    }

    @Test
    void authException_403_mapsToExitAuth() {
        var exit = CliExceptionMapper.map(new CliAuthException("forbidden", 403));
        assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_AUTH);
        assertThat(exit.message()).contains("forbidden");
    }

    @Test
    void rateLimitException_mapsToExitRateLimited() {
        var exit = CliExceptionMapper.map(new CliRateLimitException("slow down"));
        assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_RATE_LIMITED);
        assertThat(exit.message()).contains("Rate limited");
        assertThat(exit.message()).contains("slow down");
    }

    @Test
    void conflictException_mapsToExitConflict() {
        var exit = CliExceptionMapper.map(new CliConflictException("session already active"));
        assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_CONFLICT);
        assertThat(exit.message()).contains("Conflict");
        assertThat(exit.message()).contains("session already active");
    }

    @Test
    void validationException_mapsToExitValidation() {
        var exit = CliExceptionMapper.map(new CliValidationException("content too long"));
        assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_VALIDATION);
        assertThat(exit.message()).contains("Validation error");
        assertThat(exit.message()).contains("content too long");
    }

    @Test
    void notFoundException_mapsToExitApiError() {
        var exit = CliExceptionMapper.map(new CliNotFoundException("session xyz"));
        assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_API_ERROR);
        assertThat(exit.message()).contains("Not found");
        assertThat(exit.message()).contains("session xyz");
    }

    @Test
    void genericApiException_mapsToExitApiError() {
        var exit = CliExceptionMapper.map(new CliApiException("something broke", 500,
            CliApiException.ErrorType.SERVER_ERROR));
        assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_API_ERROR);
        assertThat(exit.message()).contains("something broke");
    }

    @Test
    void timeoutException_mapsToExitTimeout() {
        var exit = CliExceptionMapper.map(new TimeoutException());
        assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_TIMEOUT);
        assertThat(exit.message()).contains("timed out");
    }

    @Test
    void connectException_mapsToExitConnect() {
        var exit = CliExceptionMapper.map(new ConnectException("refused"));
        assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_CONNECT);
        assertThat(exit.message()).contains("Connection refused");
        assertThat(exit.message()).contains("refused");
    }

    @Test
    void unexpectedException_mapsToExitApiError() {
        var exit = CliExceptionMapper.map(new RuntimeException("boom"));
        assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_API_ERROR);
        assertThat(exit.message()).contains("Unexpected error");
        assertThat(exit.message()).contains("boom");
    }

    @Test
    void exitCodes_areDistinct() {
        assertThat(CliExceptionMapper.EXIT_API_ERROR).isEqualTo(1);
        assertThat(CliExceptionMapper.EXIT_AUTH).isEqualTo(3);
        assertThat(CliExceptionMapper.EXIT_RATE_LIMITED).isEqualTo(4);
        assertThat(CliExceptionMapper.EXIT_CONFLICT).isEqualTo(5);
        assertThat(CliExceptionMapper.EXIT_VALIDATION).isEqualTo(6);
        assertThat(CliExceptionMapper.EXIT_TIMEOUT).isEqualTo(7);
        assertThat(CliExceptionMapper.EXIT_CONNECT).isEqualTo(8);
    }
}
