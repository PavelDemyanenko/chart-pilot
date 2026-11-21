package com.chartpilot.bridge.ws;

import java.time.Duration;

public class CommandTimeoutException extends RuntimeException {

    public CommandTimeoutException(String action, Duration timeout) {
        super("Browser did not respond to " + action + " within " + timeout.toSeconds() + "s");
    }
}