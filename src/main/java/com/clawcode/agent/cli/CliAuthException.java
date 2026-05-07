package com.clawcode.agent.cli;

public class CliAuthException extends CliApiException {

    public CliAuthException(String message) {
        super(message, 401, ErrorType.AUTH);
    }

    public CliAuthException(String message, int statusCode) {
        super(message, statusCode, ErrorType.AUTH);
    }
}
