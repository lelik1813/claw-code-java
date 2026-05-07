package com.clawcode.agent.cli;

public class CliConflictException extends CliApiException {

    public CliConflictException(String message) {
        super(message, 409, ErrorType.CONFLICT);
    }
}
