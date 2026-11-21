package com.reliefgrid;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchingWorkerTest {
  private StorageCoordinator storage;
  private MatchingService matching;
  private MatchingWorker worker;

  @BeforeEach
  void setUp() {
    storage = mock(StorageCoordinator.class);
    matching = mock(MatchingService.class);
    worker = new MatchingWorker(storage, matching);
  }

  @Test
  void requestCreatedEventMatchesPendingRequest() {
    Map<String, Object> request = Map.of("request_id", "req-1", "status", "PENDING");
    when(storage.get("req-1", "requests")).thenReturn(request);

    worker.handle(Map.of("event_type", "request.created", "request_id", "req-1"));

    verify(matching).match(request);
  }

  @Test
  void requestCreatedEventMatchesProcessingRequestRecoveryState() {
    Map<String, Object> request = Map.of("request_id", "req-1", "status", "PROCESSING");
    when(storage.get("req-1", "requests")).thenReturn(request);

    worker.handle(Map.of("event_type", "request.created", "request_id", "req-1"));

    verify(matching).match(request);
  }

  @Test
  void requestCreatedEventIgnoresCompletedRequest() {
    Map<String, Object> request = Map.of("request_id", "req-1", "status", "COMPLETED");
    when(storage.get("req-1", "requests")).thenReturn(request);

    worker.handle(Map.of("event_type", "request.created", "request_id", "req-1"));

    verify(matching, never()).match(request);
  }

  @Test
  void requestCreatedEventIgnoresMissingRequest() {
    when(storage.get("missing", "requests")).thenReturn(null);

    worker.handle(Map.of("event_type", "request.created", "request_id", "missing"));

    verifyNoInteractions(matching);
  }

  @Test
  void resourceCreatedEventRematchesOnlyPendingRequests() {
    Map<String, Object> resource = Map.of("resource_id", "res-1", "resource_type", "water");
    Map<String, Object> pending = Map.of("request_id", "req-1", "status", "PENDING", "request_type", "water");
    Map<String, Object> processing = Map.of("request_id", "req-2", "status", "PROCESSING", "request_type", "water");
    Map<String, Object> completed = Map.of("request_id", "req-3", "status", "COMPLETED", "request_type", "water");

    when(storage.get("res-1", "resources")).thenReturn(resource);
    when(storage.list("requests", Map.of("request_type", "water")))
        .thenReturn(new ArrayList<>(java.util.List.of(completed, processing, pending)));

    worker.handle(Map.of("event_type", "resource.created", "resource_id", "res-1"));

    verify(matching).match(pending);
    verify(matching, never()).match(processing);
    verify(matching, never()).match(completed);
  }

  @Test
  void unsupportedEventIsRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> worker.handle(Map.of("event_type", "unknown.event", "request_id", "req-1")));
  }

  @Test
  void scheduledRecoveryDelegatesToMatchingService() {
    worker.recoverStaleMatchingAttempts();

    verify(matching).recoverStaleAttempts(any(Duration.class));
  }
}
