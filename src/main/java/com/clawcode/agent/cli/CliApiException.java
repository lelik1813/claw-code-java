package com.clawcode.agent.cli;

/**
 * Base exception for claw-code-java API errors.
 * Carries HTTP status code and structured error type so the mapper
 * doesn't need to parse message text.
 */
public class CliApiException extends RuntimeException {

    private final int statusCode;
    private final ErrorType errorType;

    public enum ErrorType {
        AUTH, FORBIDDEN, NOT_FOUND, CONFLICT, VALIDATION, RATE_LIMITED, SERVER_ERROR, TRANSPORT
    }

    public CliApiException(String message) {
        this(message, 0, null);
    }

    public CliApiException(String message, int statusCode, ErrorType errorType) {
        super(message);
        this.statusCode = statusCode;
        this.errorType = errorType;
    }

    public CliApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.errorType = ErrorType.TRANSPORT;
    }

    public int statusCode() { return statusCode; }
    public ErrorType errorType() { return errorType; }
}
