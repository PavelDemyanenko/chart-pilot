package com.chartpilot.connector.sync;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.chartpilot.connector.config.ConnectorProperties;
import com.chartpilot.connector.model.Watermark;
import com.chartpilot.connector.index.IndexInitializer;
import com.chartpilot.connector.model.Order;
import com.chartpilot.connector.repository.CheckpointRepository;
import com.chartpilot.connector.repository.OrderRepository;
import com.chartpilot.connector.repository.SyncDlqRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.OperationType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncServiceTest {

    private static final ConnectorProperties PROPERTIES = new ConnectorProperties("pg-orders", 2, 5000, 2);

    @Mock
    private OrderRepository orders;

    @Mock
    private OpenSearchClient client;

    @Mock
    private IndexInitializer indexInitializer;

    @Mock
    private CheckpointRepository checkpoint;

    @Mock
    private SyncDlqRepository dlq;

    @Mock
    private AdvisorySyncLock advisoryLock;

    private SyncStatusTracker tracker;
    private SyncService service;

    @BeforeEach
    void setUp() {
        tracker = new SyncStatusTracker(Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC));
        service = new SyncService(orders, client, indexInitializer, PROPERTIES, tracker,
                checkpoint, dlq, advisoryLock, new SimpleMeterRegistry());
        lenient().when(advisoryLock.tryLock()).thenReturn(true);
        lenient().when(orders.fetchDeletedIds(anyInt(), anyInt())).thenReturn(List.of());
    }

    @Test
    void initialLoadIndexesBatchesAdvancesWatermarkAndSavesCheckpoint() throws IOException {
        Order first = order(1, 10);
        Order second = order(2, 20);
        Order third = order(3, 30);
        when(orders.fetchAfter(Watermark.initial(), 2, 2)).thenReturn(List.of(first, second));
        when(orders.fetchAfter(new Watermark(second.updatedAt(), 2), 2, 2)).thenReturn(List.of(third));
        when(client.bulk(any(BulkRequest.class))).thenReturn(bulkOk());

        service.initialLoad();

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client, times(2)).bulk(captor.capture());
        List<BulkRequest> requests = captor.getAllValues();
        assertThat(requests.get(0).operations()).hasSize(2);
        assertThat(requests.get(0).operations().get(0).index().id()).isEqualTo("1");
        assertThat(requests.get(1).operations().getFirst().index().id()).isEqualTo("3");

        assertThat(service.currentWatermark()).isEqualTo(new Watermark(third.updatedAt(), 3));
        verify(checkpoint).save(new Watermark(second.updatedAt(), 2));
        verify(checkpoint).save(new Watermark(third.updatedAt(), 3));
        SyncStatusTracker.SyncStatus status = tracker.snapshot();
        assertThat(status.syncedRecords()).isEqualTo(3);
        assertThat(status.initialLoadCompleted()).isTrue();
        assertThat(status.lastError()).isNull();
    }

    @Test
    void transientBulkErrorsDoNotAdvanceWatermark() throws IOException {
        Order first = order(1, 10);
        when(orders.fetchAfter(Watermark.initial(), 2, 2)).thenReturn(List.of(first));
        when(client.bulk(any(BulkRequest.class))).thenReturn(bulkError(503));

        service.initialLoad();

        assertThat(service.currentWatermark()).isEqualTo(Watermark.initial());
        assertThat(tracker.snapshot().lastError()).contains("Transient bulk errors");
        verify(dlq, never()).record(anyLong(), any());
        verify(checkpoint, never()).save(any());
    }

    @Test
    void permanentBulkErrorGoesToDlqAndWatermarkAdvances() throws IOException {
        Order first = order(1, 10);
        when(orders.fetchAfter(Watermark.initial(), 2, 2)).thenReturn(List.of(first));
        when(client.bulk(any(BulkRequest.class))).thenReturn(bulkError(400));

        service.initialLoad();

        verify(dlq).record(eq(1L), contains("mapper_parsing_exception"));
        assertThat(service.currentWatermark()).isEqualTo(new Watermark(first.updatedAt(), 1));
        SyncStatusTracker.SyncStatus status = tracker.snapshot();
        assertThat(status.syncedRecords()).isEqualTo(1);
        assertThat(status.lastError()).isNull();
    }

    @Test
    void repositoryFailureIsRecordedAndNotThrown() {
        when(orders.fetchAfter(any(Watermark.class), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("pg down"));

        assertThatCode(() -> service.initialLoad()).doesNotThrowAnyException();

        SyncStatusTracker.SyncStatus status = tracker.snapshot();
        assertThat(status.lastError()).contains("pg down");
        assertThat(status.syncedRecords()).isZero();
    }

    @Test
    void pollContinuesFromCurrentWatermark() throws IOException {
        Order first = order(1, 10);
        when(orders.fetchAfter(Watermark.initial(), 2, 2)).thenReturn(List.of(first));
        when(orders.fetchAfter(new Watermark(first.updatedAt(), 1), 2, 2)).thenReturn(List.of());
        when(client.bulk(any(BulkRequest.class))).thenReturn(bulkOk());

        service.initialLoad();
        service.poll();

        verify(orders).fetchAfter(new Watermark(first.updatedAt(), 1), 2, 2);
        assertThat(tracker.snapshot().syncCycles()).isEqualTo(2);
    }

    @Test
    void pollSkipsWhenAnotherInstanceHoldsAdvisoryLock() {
        when(advisoryLock.tryLock()).thenReturn(false);

        service.poll();

        verifyNoInteractions(orders);
        assertThat(tracker.snapshot().syncCycles()).isZero();
    }

    private static Order order(long id, int minute) {
        OffsetDateTime timestamp = OffsetDateTime.of(2026, 11, 1, 10, minute, 0, 0, ZoneOffset.UTC);
        return new Order(id, "EU", "books", "NEW", new BigDecimal("10.00"), timestamp, timestamp);
    }

    private static BulkResponse bulkOk() {
        return new BulkResponse.Builder().took(1).errors(false).items(List.of()).build();
    }

    private static BulkResponse bulkError(int status) {
        BulkResponseItem item = new BulkResponseItem.Builder()
                .operationType(OperationType.Index)
                .index("pg-orders")
                .id("1")
                .status(status)
                .error(error -> error.type("mapper_parsing_exception").reason("failed to parse"))
                .build();
        return new BulkResponse.Builder().took(1).errors(true).items(List.of(item)).build();
    }
}