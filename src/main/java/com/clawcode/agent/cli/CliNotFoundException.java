package com.clawcode.agent.cli;

public class CliNotFoundException extends CliApiException {

    public CliNotFoundException(String message) {
        super(message, 404, ErrorType.NOT_FOUND);
    }
}
