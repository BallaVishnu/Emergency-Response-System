package com.reliefgrid;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MatchingService {
  private final StorageCoordinator storage;
  private final AppProperties properties;

  MatchingService(StorageCoordinator storage, AppProperties properties) {
    this.storage = storage;
    this.properties = properties;
  }

  public Map<String, Object> match(Map<String, Object> request) {
    String requestId = (String) request.get("request_id");

    if (request.get("assigned_resource_id") != null) {
      return result(
          requestId,
          String.valueOf(request.get("status")),
          request.get("assigned_resource_id"),
          null,
          "Request already assigned");
    }

    String attemptId = UUID.randomUUID().toString();
    Map<String, Object> claimed = storage.claimRequest(requestId, attemptId);

    if (claimed == null) {
      Map<String, Object> current = storage.get(requestId, "requests");
      if (current == null) {
        return result(requestId, "NOT_FOUND", null, null, "Request no longer exists");
      }

      if (current.get("assigned_resource_id") != null) {
        return result(
            requestId,
            String.valueOf(current.get("status")),
            current.get("assigned_resource_id"),
            null,
            "Request already assigned");
      }

      String status = String.valueOf(current.get("status"));
      String message = "PROCESSING".equals(status)
          ? "A matching attempt is already in progress"
          : "Request is not eligible for matching";
      return result(requestId, status, null, null, message);
    }

    int quantity = ((Number) claimed.get("quantity")).intValue();
    List<Map<String, Object>> candidates = storage.list("resources", Map.of(
        "resource_type", claimed.get("request_type"),
        "availability_status", "AVAILABLE"));
    candidates.sort(Comparator.comparingDouble(candidate -> distance(claimed, candidate)));

    for (Map<String, Object> candidate : candidates) {
      double distance = distance(claimed, candidate);
      if (distance > properties.matchingRadiusKm()) {
        continue;
      }

      String resourceId = (String) candidate.get("resource_id");
      Map<String, Object> reserved;

      try {
        reserved = storage.reserveResource(resourceId, requestId, attemptId, quantity);
      } catch (RuntimeException reserveError) {
        compensate(requestId, attemptId, resourceId, reserveError);
        throw reserveError;
      }

      if (reserved == null) {
        continue;
      }

      Map<String, Object> finalized;
      try {
        finalized = storage.finalizeMatch(requestId, attemptId, resourceId);
      } catch (RuntimeException finalizeError) {
        Map<String, Object> resolved = resolveFailedFinalization(
            requestId, attemptId, resourceId, finalizeError);

        if (resolved != null) {
          storage.finishReservation(resourceId, requestId, attemptId);
          return result(
              requestId,
              "MATCHED",
              resourceId,
              Math.round(distance * 100d) / 100d,
              "Closest available resource assigned");
        }

        Map<String, Object> current = storage.get(requestId, "requests");
        String status = current == null ? "NOT_FOUND" : String.valueOf(current.get("status"));
        return result(
            requestId,
            status,
            current == null ? null : current.get("assigned_resource_id"),
            null,
            "Match attempt was rolled back; the request is pending and can be retried");
      }

      if (finalized == null) {
        Map<String, Object> current = storage.get(requestId, "requests");

        if (isMatchedTo(current, resourceId)) {
          storage.finishReservation(resourceId, requestId, attemptId);
          return result(
              requestId,
              "MATCHED",
              resourceId,
              Math.round(distance * 100d) / 100d,
              "Closest available resource assigned");
        }

        storage.releaseReservation(resourceId, requestId, attemptId);
        storage.markPending(requestId, attemptId);
        current = storage.get(requestId, "requests");
        return result(
            requestId,
            current == null ? "NOT_FOUND" : String.valueOf(current.get("status")),
            null,
            null,
            "Request changed during matching; the resource reservation was released");
      }

      storage.finishReservation(resourceId, requestId, attemptId);
      return result(
          requestId,
          "MATCHED",
          resourceId,
          Math.round(distance * 100d) / 100d,
          "Closest available resource assigned");
    }

    storage.markPending(requestId, attemptId);
    Map<String, Object> current = storage.get(requestId, "requests");
    String status = current == null ? "NOT_FOUND" : String.valueOf(current.get("status"));

    return result(
        requestId,
        status,
        current == null ? null : current.get("assigned_resource_id"),
        null,
        "No suitable available resource found; request remains eligible for a later retry");
  }

  private Map<String, Object> resolveFailedFinalization(
      String requestId,
      String attemptId,
      String resourceId,
      RuntimeException failure) {
    try {
      Map<String, Object> current = storage.get(requestId, "requests");
      if (isMatchedTo(current, resourceId)) {
        return current;
      }

      storage.releaseReservation(resourceId, requestId, attemptId);
      storage.markPending(requestId, attemptId);
      return null;
    } catch (RuntimeException compensationError) {
      failure.addSuppressed(compensationError);
      throw new IllegalStateException(
          "Match finalization failed and compensation could not complete; recovery will reconcile the attempt",
          failure);
    }
  }

  private void compensate(
      String requestId,
      String attemptId,
      String resourceId,
      RuntimeException failure) {
    try {
      storage.releaseReservation(resourceId, requestId, attemptId);
      storage.markPending(requestId, attemptId);
    } catch (RuntimeException compensationError) {
      failure.addSuppressed(compensationError);
      throw new IllegalStateException(
          "Resource reservation outcome is uncertain and compensation could not complete; recovery will reconcile the attempt",
          failure);
    }
  }

  private boolean isMatchedTo(Map<String, Object> request, String resourceId) {
    return request != null
        && "MATCHED".equals(request.get("status"))
        && resourceId.equals(request.get("assigned_resource_id"));
  }

  public void recoverStaleAttempts(Duration timeout) {
    Instant cutoff = Instant.now().minus(timeout);
    List<String> recoveredRequestIds = new ArrayList<>();

    for (Map<String, Object> request : storage.list("requests", Map.of("status", "PROCESSING"))) {
      String startedAt = String.valueOf(request.get("match_attempt_started_at"));
      String attemptId = (String) request.get("match_attempt_id");

      if (attemptId == null || "null".equals(startedAt)) {
        continue;
      }

      try {
        if (Instant.parse(startedAt).isBefore(cutoff)) {
          String requestId = (String) request.get("request_id");
          if (storage.abandonStaleAttempt(requestId, attemptId, cutoff)) {
            recoveredRequestIds.add(requestId);
          }
        }
      } catch (RuntimeException ignored) {
        // Keep recovery running; a later scheduled pass will retry this request.
      }
    }

    storage.reconcileReservations();

    for (String requestId : recoveredRequestIds) {
      Map<String, Object> request = storage.get(requestId, "requests");
      if (request != null && "PENDING".equals(request.get("status"))) {
        match(request);
      }
    }
  }

  public Map<String, Object> currentMatch(Map<String, Object> request) {
    return result(
        (String) request.get("request_id"),
        String.valueOf(request.get("status")),
        request.get("assigned_resource_id"),
        null,
        request.get("assigned_resource_id") == null ? "No assignment" : "Assignment found");
  }

  private Map<String, Object> result(
      String id,
      String status,
      Object resource,
      Double distance,
      String message) {
    var result = new LinkedHashMap<String, Object>();
    result.put("request_id", id);
    result.put("status", status);
    result.put("resource_id", resource);

    if (distance != null) {
      result.put("distance_km", distance);
    }

    result.put("message", message);
    return result;
  }

  private double distance(Map<String, Object> first, Map<String, Object> second) {
    double lat1 = Math.toRadians(((Number) first.get("latitude")).doubleValue());
    double lon1 = Math.toRadians(((Number) first.get("longitude")).doubleValue());
    double lat2 = Math.toRadians(((Number) second.get("latitude")).doubleValue());
    double lon2 = Math.toRadians(((Number) second.get("longitude")).doubleValue());

    double value = Math.pow(Math.sin((lat2 - lat1) / 2), 2)
        + Math.cos(lat1) * Math.cos(lat2)
            * Math.pow(Math.sin((lon2 - lon1) / 2), 2);

    return 6371d * 2 * Math.asin(Math.sqrt(value));
  }
}
