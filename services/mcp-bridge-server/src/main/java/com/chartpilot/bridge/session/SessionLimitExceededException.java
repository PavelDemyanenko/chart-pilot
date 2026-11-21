package com.chartpilot.bridge.session;

public class SessionLimitExceededException extends RuntimeException {

    public SessionLimitExceededException(int maxSessions) {
        super("Session limit reached (" + maxSessions + "); try again later");
    }
}