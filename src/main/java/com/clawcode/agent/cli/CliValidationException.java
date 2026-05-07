package com.clawcode.agent.cli;

public class CliValidationException extends CliApiException {

    public CliValidationException(String message) {
        super(message, 422, ErrorType.VALIDATION);
    }
}
