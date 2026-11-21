package com.chartpilot.bridge.ws;

public class UnknownSessionException extends RuntimeException {

    private final String sessionId;

    public UnknownSessionException(String sessionId) {
        super("Session not found: " + sessionId);
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }
}