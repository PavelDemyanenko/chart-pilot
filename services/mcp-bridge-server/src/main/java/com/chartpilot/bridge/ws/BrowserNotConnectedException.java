package com.chartpilot.bridge.ws;

public class BrowserNotConnectedException extends RuntimeException {

    private final String sessionId;

    public BrowserNotConnectedException(String sessionId) {
        super("Browser is not connected for session: " + sessionId);
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }
}