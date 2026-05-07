package com.clawcode.agent.core.tasks;

public class RemoteTaskException extends RuntimeException {

    public RemoteTaskException(String message) {
        super(message);
    }

    public RemoteTaskException(String message, Throwable cause) {
        super(message, cause);
    }
}
