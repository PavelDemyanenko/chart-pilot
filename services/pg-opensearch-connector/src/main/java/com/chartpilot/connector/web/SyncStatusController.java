package com.chartpilot.connector.web;

import com.chartpilot.connector.sync.SyncStatusTracker;
import com.chartpilot.connector.sync.SyncStatusTracker.SyncStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
public class SyncStatusController {

    private final SyncStatusTracker tracker;

    public SyncStatusController(SyncStatusTracker tracker) {
        this.tracker = tracker;
    }

    @GetMapping("/status")
    public SyncStatus status() {
        return tracker.snapshot();
    }
}