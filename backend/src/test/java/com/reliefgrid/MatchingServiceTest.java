package com.reliefgrid;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchingServiceTest {
  private StorageCoordinator storage;
  private MatchingService matching;

  @BeforeEach
  void setUp() {
    storage = mock(StorageCoordinator.class);
    AppProperties properties = new AppProperties("api",
        new AppProperties.Mongo("emergency", "mongo0", "mongo1", "mongo2"), 100, 30);
    matching = new MatchingService(storage, properties);
  }

  @Test
  void returnsExistingAssignmentWithoutMatchingAgain() {
    Map<String, Object> request = request("req-1", "MATCHED");
    request.put("assigned_resource_id", "res-1");

    Map<String, Object> result = matching.match(request);

    assertEquals("MATCHED", result.get("status"));
    assertEquals("req-1", result.get("request_id"));
    assertEquals("res-1", result.get("resource_id"));
    verifyNoInteractions(storage);
  }

  @Test
  void assignsClosestResourceAndFinishesReservation() {
    Map<String, Object> request = request("req-1", "PENDING");
    Map<String, Object> claimed = new LinkedHashMap<>(request);
    claimed.put("status", "PROCESSING");
    Map<String, Object> close = resource("res-close", 13.03, 77.57, 20);
    Map<String, Object> far = resource("res-far", 13.20, 77.80, 20);

    when(storage.claimRequest(eq("req-1"), anyString())).thenReturn(claimed);
    when(storage.list("resources", Map.of("resource_type", "water", "availability_status", "AVAILABLE")))
        .thenReturn(new ArrayList<>(List.of(far, close)));
    when(storage.reserveResource(eq("res-close"), eq("req-1"), anyString(), eq(5))).thenReturn(close);
    when(storage.finalizeMatch(eq("req-1"), anyString(), eq("res-close"))).thenReturn(request);

    Map<String, Object> result = matching.match(request);

    assertEquals("MATCHED", result.get("status"));
    assertEquals("res-close", result.get("resource_id"));
    verify(storage).finishReservation(eq("res-close"), eq("req-1"), anyString());
  }

  @Test
  void leavesRequestPendingWhenNoResourceIsInRange() {
    Map<String, Object> request = request("req-1", "PENDING");
    Map<String, Object> claimed = new LinkedHashMap<>(request);
    claimed.put("status", "PROCESSING");
    Map<String, Object> far = resource("res-far", 20.00, 80.00, 20);

    when(storage.claimRequest(eq("req-1"), anyString())).thenReturn(claimed);
    when(storage.list("resources", Map.of("resource_type", "water", "availability_status", "AVAILABLE")))
        .thenReturn(new ArrayList<>(List.of(far)));
    when(storage.markPending(eq("req-1"), anyString())).thenReturn(true);
    when(storage.get("req-1", "requests")).thenReturn(request);

    Map<String, Object> result = matching.match(request);

    assertEquals("PENDING", result.get("status"));
    assertNull(result.get("resource_id"));
    verify(storage).markPending(eq("req-1"), anyString());
    verify(storage, never()).reserveResource(anyString(), anyString(), anyString(), anyInt());
  }

  @Test
  void skipsResourceWhenAtomicReservationFails() {
    Map<String, Object> request = request("req-1", "PENDING");
    Map<String, Object> claimed = new LinkedHashMap<>(request);
    claimed.put("status", "PROCESSING");
    Map<String, Object> candidate = resource("res-1", 13.03, 77.57, 20);

    when(storage.claimRequest(eq("req-1"), anyString())).thenReturn(claimed);
    when(storage.list("resources", Map.of("resource_type", "water", "availability_status", "AVAILABLE")))
        .thenReturn(new ArrayList<>(List.of(candidate)));
    when(storage.reserveResource(eq("res-1"), eq("req-1"), anyString(), eq(5))).thenReturn(null);
    when(storage.markPending(eq("req-1"), anyString())).thenReturn(true);
    when(storage.get("req-1", "requests")).thenReturn(request);

    Map<String, Object> result = matching.match(request);

    assertEquals("PENDING", result.get("status"));
  }

  @Test
  void compensatesReservationWhenFinalizationFails() {
    Map<String, Object> request = request("req-1", "PENDING");
    Map<String, Object> claimed = new LinkedHashMap<>(request);
    claimed.put("status", "PROCESSING");
    Map<String, Object> candidate = resource("res-1", 13.03, 77.57, 20);

    when(storage.claimRequest(eq("req-1"), anyString())).thenReturn(claimed);
    when(storage.list("resources", Map.of("resource_type", "water", "availability_status", "AVAILABLE")))
        .thenReturn(new ArrayList<>(List.of(candidate)));
    when(storage.reserveResource(eq("res-1"), eq("req-1"), anyString(), eq(5))).thenReturn(candidate);
    when(storage.finalizeMatch(eq("req-1"), anyString(), eq("res-1")))
        .thenReturn(null);
    when(storage.get("req-1", "requests")).thenReturn(request);

    Map<String, Object> result = matching.match(request);

    assertEquals("PENDING", result.get("status"));
    verify(storage).releaseReservation(eq("res-1"), eq("req-1"), anyString());
    verify(storage).markPending(eq("req-1"), anyString());
  }

  @Test
  void reportsProcessingConflictWhenClaimCannotBeObtained() {
    Map<String, Object> request = request("req-1", "PENDING");
    Map<String, Object> processing = requestWithStatus("PROCESSING");

    when(storage.claimRequest(eq("req-1"), anyString())).thenReturn(null);
    when(storage.get("req-1", "requests")).thenReturn(processing);

    Map<String, Object> result = matching.match(request);

    assertEquals("PROCESSING", result.get("status"));
    assertTrue(result.get("message").toString().contains("already in progress"));
  }

  @Test
  void staleAttemptRecoveryAbandonsReconcilesAndRetriesRequest() {
    Map<String, Object> stale = request("req-stale", "PROCESSING");
    stale.put("match_attempt_id", "attempt-1");
    stale.put("match_attempt_started_at", "2020-01-01T00:00:00Z");

    when(storage.list("requests", Map.of("status", "PROCESSING")))
        .thenReturn(new ArrayList<>(List.of(stale)));
    when(storage.abandonStaleAttempt(eq("req-stale"), eq("attempt-1"), any()))
        .thenReturn(true);

    Map<String, Object> recovered = request("req-stale", "PENDING");
    when(storage.get("req-stale", "requests")).thenReturn(recovered);

    MatchingService matchingSpy = spy(matching);
    doReturn(Map.of("request_id", "req-stale", "status", "PENDING"))
        .when(matchingSpy)
        .match(recovered);

    matchingSpy.recoverStaleAttempts(Duration.ofSeconds(120));

    verify(storage).abandonStaleAttempt(eq("req-stale"), eq("attempt-1"), any());
    verify(storage).reconcileReservations();
    verify(storage).get("req-stale", "requests");
    verify(matchingSpy).match(recovered);
  }

  @Test
  void currentMatchReportsAssignmentAndNoAssignment() {
    Map<String, Object> assigned = request("req-1", "MATCHED");
    assigned.put("assigned_resource_id", "res-1");

    assertEquals("res-1", matching.currentMatch(assigned).get("resource_id"));
    assertEquals("Assignment found", matching.currentMatch(assigned).get("message"));

    Map<String, Object> unassigned = request("req-2", "PENDING");
    assertNull(matching.currentMatch(unassigned).get("resource_id"));
  }

  private Map<String, Object> request(String id, String status) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("request_id", id);
    request.put("request_type", "water");
    request.put("latitude", 13.02);
    request.put("longitude", 77.55);
    request.put("quantity", 5);
    request.put("status", status);
    request.put("assigned_resource_id", null);
    return request;
  }

  private Map<String, Object> requestWithStatus(String status) {
    Map<String, Object> request = request("req-1", status);
    request.put("status", status);
    return request;
  }

  private Map<String, Object> resource(String id, double latitude, double longitude, int quantity) {
    Map<String, Object> resource = new LinkedHashMap<>();
    resource.put("resource_id", id);
    resource.put("resource_type", "water");
    resource.put("latitude", latitude);
    resource.put("longitude", longitude);
    resource.put("availability_status", "AVAILABLE");
    resource.put("available_quantity", quantity);
    return resource;
  }
}
