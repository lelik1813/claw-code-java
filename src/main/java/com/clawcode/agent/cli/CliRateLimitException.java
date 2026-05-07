package com.clawcode.agent.cli;

public class CliRateLimitException extends CliApiException {

    public CliRateLimitException(String message) {
        super(message, 429, ErrorType.RATE_LIMITED);
    }
}
