package com.chartpilot.connector.sync;

import java.time.Clock;
import java.time.Instant;

import com.chartpilot.connector.model.Watermark;

import org.springframework.stereotype.Component;

@Component
public class SyncStatusTracker {

    public record SyncStatus(
            long syncedRecords,
            long deletedRecords,
            String lastWatermarkUpdatedAt,
            long lastWatermarkId,
            String lastSyncAt,
            String lastError,
            boolean initialLoadCompleted,
            long syncCycles) {
    }

    private final Clock clock;

    private long syncedRecords;
    private long deletedRecords;
    private Watermark lastWatermark = Watermark.initial();
    private Instant lastSyncAt;
    private String lastError;
    private boolean initialLoadCompleted;
    private long syncCycles;

    public SyncStatusTracker(Clock clock) {
        this.clock = clock;
    }

    public synchronized void recordBatch(int records, Watermark watermark) {
        syncedRecords += records;
        lastWatermark = watermark;
    }

    public synchronized void recordDeleted(int records) {
        deletedRecords += records;
    }

    public synchronized void recordCycleSuccess(boolean initialLoad) {
        syncCycles++;
        lastSyncAt = clock.instant();
        lastError = null;
        if (initialLoad) {
            initialLoadCompleted = true;
        }
    }

    public synchronized void recordError(Exception error) {
        lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    public synchronized SyncStatus snapshot() {
        return new SyncStatus(
                syncedRecords,
                deletedRecords,
                lastWatermark.updatedAt().toString(),
                lastWatermark.id(),
                lastSyncAt != null ? lastSyncAt.toString() : null,
                lastError,
                initialLoadCompleted,
                syncCycles);
    }
}