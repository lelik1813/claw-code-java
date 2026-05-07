package com.clawcode.agent.cli;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

/**
 * Maps CLI exceptions to user-friendly messages and exit codes.
 * Single place for all error → exit-code/message decisions.
 */
public final class CliExceptionMapper {

    public static final int EXIT_API_ERROR = 1;
    public static final int EXIT_AUTH = 3;
    public static final int EXIT_RATE_LIMITED = 4;
    public static final int EXIT_CONFLICT = 5;
    public static final int EXIT_VALIDATION = 6;
    public static final int EXIT_TIMEOUT = 7;
    public static final int EXIT_CONNECT = 8;

    private CliExceptionMapper() {}

    public static CliExit map(Exception e) {
        if (e instanceof CliAuthException) {
            return new CliExit(EXIT_AUTH, "Authentication failed: " + e.getMessage());
        }
        if (e instanceof CliRateLimitException) {
            return new CliExit(EXIT_RATE_LIMITED, "Rate limited: " + e.getMessage());
        }
        if (e instanceof CliConflictException) {
            return new CliExit(EXIT_CONFLICT, "Conflict: " + e.getMessage());
        }
        if (e instanceof CliValidationException) {
            return new CliExit(EXIT_VALIDATION, "Validation error: " + e.getMessage());
        }
        if (e instanceof CliNotFoundException) {
            return new CliExit(EXIT_API_ERROR, "Not found: " + e.getMessage());
        }
        if (e instanceof CliApiException) {
            return new CliExit(EXIT_API_ERROR, e.getMessage());
        }
        if (e instanceof TimeoutException) {
            return new CliExit(EXIT_TIMEOUT, "Request timed out");
        }
        if (e instanceof ConnectException) {
            return new CliExit(EXIT_CONNECT, "Connection refused: " + e.getMessage());
        }
        return new CliExit(EXIT_API_ERROR, "Unexpected error: " + e.getMessage());
    }

    public record CliExit(int code, String message) {}
}
