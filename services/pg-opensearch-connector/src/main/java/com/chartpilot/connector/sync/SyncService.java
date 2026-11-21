package com.chartpilot.connector.sync;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import com.chartpilot.connector.config.ConnectorProperties;
import com.chartpilot.connector.index.IndexInitializer;
import com.chartpilot.connector.model.Order;
import com.chartpilot.connector.model.Watermark;
import com.chartpilot.connector.repository.CheckpointRepository;
import com.chartpilot.connector.repository.OrderRepository;
import com.chartpilot.connector.repository.SyncDlqRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.functions.CheckedRunnable;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final OrderRepository orders;
    private final OpenSearchClient client;
    private final IndexInitializer indexInitializer;
    private final ConnectorProperties properties;
    private final SyncStatusTracker tracker;
    private final CheckpointRepository checkpoint;
    private final SyncDlqRepository dlq;
    private final AdvisorySyncLock advisoryLock;
    private final Counter bulkRecords;
    private final Counter bulkErrors;
    private final Counter bulkDeleted;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    private final ReentrantLock syncLock = new ReentrantLock();
    private volatile Watermark watermark = Watermark.initial();
    private volatile boolean checkpointLoaded;

    public SyncService(
            OrderRepository orders,
            OpenSearchClient client,
            IndexInitializer indexInitializer,
            ConnectorProperties properties,
            SyncStatusTracker tracker,
            CheckpointRepository checkpoint,
            SyncDlqRepository dlq,
            AdvisorySyncLock advisoryLock,
            MeterRegistry metrics) {
        this.orders = orders;
        this.client = client;
        this.indexInitializer = indexInitializer;
        this.properties = properties;
        this.tracker = tracker;
        this.checkpoint = checkpoint;
        this.dlq = dlq;
        this.advisoryLock = advisoryLock;
        this.bulkRecords = Counter.builder("chartpilot.connector.bulk.records").register(metrics);
        this.bulkErrors = Counter.builder("chartpilot.connector.bulk.errors").register(metrics);
        this.bulkDeleted = Counter.builder("chartpilot.connector.bulk.deleted").register(metrics);
        // REL-02: шторм при недоступном OpenSearch гасится CircuitBreaker'ом,
        // transient-сбои — Retry'ом внутри цикла; watermark двигается только после успеха.
        this.retry = Retry.of("opensearch-bulk", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(500))
                .retryExceptions(IOException.class)
                .build());
        this.circuitBreaker = CircuitBreaker.of("opensearch-bulk", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build());
        Gauge.builder("chartpilot.connector.sync.lag.seconds", this, SyncService::syncLagSeconds).register(metrics);
    }

    // Initial load больше не блокирует ApplicationReadyEvent (REL-01):
    // первый тик @Scheduled(initialDelay=0) выполняет его асинхронно.
    @Scheduled(fixedDelayString = "${CONNECTOR_POLL_INTERVAL_MS:5000}", initialDelay = 0)
    public void poll() {
        if (!syncLock.tryLock()) {
            log.debug("Previous sync cycle still running; skipping tick");
            return;
        }
        try {
            runSyncCycle(!tracker.snapshot().initialLoadCompleted());
        } finally {
            syncLock.unlock();
        }
    }

    public void initialLoad() {
        syncLock.lock();
        try {
            runSyncCycle(true);
        } finally {
            syncLock.unlock();
        }
    }

    public Watermark currentWatermark() {
        return watermark;
    }

    private double syncLagSeconds() {
        Watermark current = watermark;
        if (current.id() == 0 && current.updatedAt().toInstant().toEpochMilli() == 0) {
            return 0;
        }
        return Math.max(0, Duration.between(current.updatedAt(), OffsetDateTime.now()).toMillis() / 1000.0);
    }

    private void runSyncCycle(boolean initialLoad) {
        if (!advisoryLock.tryLock()) {
            log.debug("Another connector instance holds the sync lock; skipping cycle");
            return;
        }
        try {
            if (!checkpointLoaded) {
                checkpoint.load().ifPresent(saved -> watermark = saved);
                checkpointLoaded = true;
            }
            indexInitializer.ensureIndex();
            while (true) {
                List<Order> batch = orders.fetchAfter(watermark, properties.batchSize(), properties.safetyLagSeconds());
                if (batch.isEmpty()) {
                    break;
                }
                bulkIndex(batch);
                Order last = batch.getLast();
                watermark = new Watermark(last.updatedAt(), last.id());
                checkpoint.save(watermark);
                tracker.recordBatch(batch.size(), watermark);
                if (batch.size() < properties.batchSize()) {
                    break;
                }
            }
            processDeletes();
            tracker.recordCycleSuccess(initialLoad);
        } catch (Exception e) {
            tracker.recordError(e);
            log.warn("Sync cycle failed (watermark stays at {}); will retry next cycle: {}", watermark, e.getMessage());
        } finally {
            advisoryLock.unlock();
        }
    }

    /**
     * DELETE-репликация: tombstone'ы удаляющей транзакции видны только после
     * commit; purge после успешного bulk-delete = прогресс (повторный delete идемпотентен).
     */
    private void processDeletes() throws Exception {
        while (true) {
            List<Long> ids = orders.fetchDeletedIds(properties.safetyLagSeconds(), properties.batchSize());
            if (ids.isEmpty()) {
                return;
            }
            bulkDelete(ids);
            orders.purgeDeleted(ids);
            bulkDeleted.increment(ids.size());
            tracker.recordDeleted(ids.size());
            if (ids.size() < properties.batchSize()) {
                return;
            }
        }
    }

    private void withResilience(CheckedRunnable operation) throws Exception {
        try {
            Retry.decorateCheckedRunnable(retry, CircuitBreaker.decorateCheckedRunnable(circuitBreaker, operation)).run();
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private void bulkIndex(List<Order> batch) throws Exception {
        withResilience(() -> doBulkIndex(batch));
    }

    private void doBulkIndex(List<Order> batch) throws IOException {
        BulkRequest.Builder request = new BulkRequest.Builder();
        for (Order order : batch) {
            request.operations(operation -> operation.index(index -> index
                    .index(properties.indexName())
                    .id(Long.toString(order.id()))
                    .document(order.toDocument())));
        }
        BulkResponse response = client.bulk(request.build());
        bulkRecords.increment(batch.size());
        if (!response.errors()) {
            return;
        }
        List<String> transientErrors = new ArrayList<>();
        for (BulkResponseItem item : response.items()) {
            if (item.error() == null) {
                continue;
            }
            bulkErrors.increment();
            String reason = item.error().type() + ": " + item.error().reason();
            if (item.status() == 400 || item.status() == 404) {
                // Перманентная ошибка (poison-pill): в DLQ, watermark двигается дальше (REL-02).
                dlq.record(Long.parseLong(item.id()), reason);
                log.error("Order {} moved to sync_dlq: {}", item.id(), reason);
            } else {
                transientErrors.add(item.id() + ": " + reason);
            }
        }
        if (!transientErrors.isEmpty()) {
            throw new IOException("Transient bulk errors, first: " + transientErrors.getFirst());
        }
    }

    private void bulkDelete(List<Long> ids) throws Exception {
        withResilience(() -> doBulkDelete(ids));
    }

    private void doBulkDelete(List<Long> ids) throws IOException {
        BulkRequest.Builder request = new BulkRequest.Builder();
        for (Long id : ids) {
            request.operations(operation -> operation.delete(delete -> delete
                    .index(properties.indexName())
                    .id(Long.toString(id))));
        }
        BulkResponse response = client.bulk(request.build());
        if (!response.errors()) {
            return;
        }
        List<String> transientErrors = new ArrayList<>();
        for (BulkResponseItem item : response.items()) {
            if (item.error() == null || item.status() == 404) {
                // 404 = документ уже удалён или не существовал: успех идемпотентного delete
                continue;
            }
            bulkErrors.increment();
            transientErrors.add(item.id() + ": " + item.error().type() + ": " + item.error().reason());
        }
        if (!transientErrors.isEmpty()) {
            throw new IOException("Transient bulk delete errors, first: " + transientErrors.getFirst());
        }
    }
}