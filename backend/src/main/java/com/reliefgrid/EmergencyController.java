package com.reliefgrid;

import com.reliefgrid.ApiModels.*;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "*")
public class EmergencyController {
  private final StorageCoordinator storage;
  private final EventPublisher events;
  private final MatchingService matching;
  private final RabbitTemplate rabbit;

  EmergencyController(StorageCoordinator storage, EventPublisher events,
      MatchingService matching, RabbitTemplate rabbit) {
    this.storage = storage;
    this.events = events;
    this.matching = matching;
    this.rabbit = rabbit;
  }

  private static String id(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  private static Map<String, Object> values(Object object) {
    Map<?, ?> converted = new com.fasterxml.jackson.databind.ObjectMapper()
        .convertValue(object, Map.class);

    var result = new LinkedHashMap<String, Object>();
    converted.forEach((key, value) -> result.put(String.valueOf(key), value));
    return result;
  }

  private static Map<String, Object> filters(Object... items) {
    var result = new LinkedHashMap<String, Object>();
    for (int i = 0; i < items.length; i += 2) {
      if (items[i + 1] != null) {
        result.put(String.valueOf(items[i]), items[i + 1]);
      }
    }
    return result;
  }

  private Map<String, Object> request(String id) {
    Map<String, Object> item = storage.get(id, "requests");
    if (item == null) {
      throw new NotFound("Request not found");
    }
    return item;
  }

  private Map<String, Object> resource(String id) {
    Map<String, Object> item = storage.get(id, "resources");
    if (item == null) {
      throw new NotFound("Resource not found");
    }
    return item;
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "ok", "service", "api");
  }

  @GetMapping("/health/ready")
  public Map<String, String> ready() {
    storage.checkReady();
    return Map.of("status", "ready", "storage", "mongodb-replica-sets");
  }

  @PostMapping("/requests")
  public ResponseEntity<Map<String, Object>> createRequest(
      @Valid @RequestBody RequestCreate payload) {
    String requestId = id("req-");
    var item = values(payload);
    String now = Instant.now().toString();

    item.put("urgency_level", payload.urgency_level() == null ? 3 : payload.urgency_level());
    item.put("request_id", requestId);
    item.put("status", "PENDING");
    item.put("assigned_resource_id", null);
    item.put("created_at", now);
    item.put("updated_at", now);

    var result = storage.put(requestId, "requests", item);
    events.requestCreated(requestId);
    return ResponseEntity.status(HttpStatus.CREATED).body(result);
  }

  @GetMapping("/requests")
  public List<Map<String, Object>> listRequests(
      @RequestParam(required = false) String request_type,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(defaultValue = "100") int limit) {
    return storage.list("requests", filters(
        "request_type", request_type,
        "status", status))
        .stream()
        .limit(Math.min(Math.max(limit, 1), 500))
        .toList();
  }

  @GetMapping("/requests/{id}")
  public Map<String, Object> getRequest(@PathVariable String id) {
    return request(id);
  }

  @PutMapping("/requests/{id}/status")
  public Map<String, Object> updateRequest(
      @PathVariable String id,
      @Valid @RequestBody StatusUpdate payload) {
    if (!Set.of("PENDING", "PROCESSING", "MATCHED", "ASSIGNED", "COMPLETED", "CANCELLED")
        .contains(payload.status())) {
      throw new IllegalArgumentException("Invalid request status");
    }

    request(id);
    return storage.updateStatus(id, "requests", payload.status());
  }

  @PostMapping("/resources")
  public ResponseEntity<Map<String, Object>> createResource(
      @Valid @RequestBody ResourceCreate payload) {
    String resourceId = id("res-");
    var item = values(payload);
    String now = Instant.now().toString();

    item.put("resource_id", resourceId);
    item.put("availability_status", "AVAILABLE");
    item.put("assigned_request_id", null);
    item.put("created_at", now);
    item.put("updated_at", now);

    var result = storage.put(resourceId, "resources", item);
    events.resourceCreated(resourceId);
    return ResponseEntity.status(HttpStatus.CREATED).body(result);
  }

  @GetMapping("/resources")
  public List<Map<String, Object>> listResources(
      @RequestParam(required = false) String resource_type,
      @RequestParam(required = false) String availability_status,
      @RequestParam(defaultValue = "100") int limit) {
    return storage.list("resources", filters(
        "resource_type", resource_type,
        "availability_status", availability_status))
        .stream()
        .limit(Math.min(Math.max(limit, 1), 500))
        .toList();
  }

  @GetMapping("/resources/{id}")
  public Map<String, Object> getResource(@PathVariable String id) {
    return resource(id);
  }

  @PutMapping("/resources/{id}/status")
  public Map<String, Object> updateResource(
      @PathVariable String id,
      @Valid @RequestBody StatusUpdate payload) {
    if (!Set.of("AVAILABLE", "RESERVED", "ASSIGNED", "UNAVAILABLE")
        .contains(payload.status())) {
      throw new IllegalArgumentException("Invalid resource status");
    }

    resource(id);
    return storage.updateStatus(id, "resources", payload.status());
  }

  @PostMapping("/match/{id}")
  public Map<String, Object> match(@PathVariable String id) {
    return matching.match(request(id));
  }

  @GetMapping("/match/{id}")
  public Map<String, Object> getMatch(@PathVariable String id) {
    return matching.currentMatch(request(id));
  }

  @GetMapping("/stats")
  public Map<String, Object> stats() {
    return storage.stats();
  }

  @GetMapping("/storage/status")
  public Map<String, Object> storageStatus() {
    return Map.of("nodes", storage.storageStatus(), "mode", "mongodb-replica-sets");
  }

  @GetMapping("/storage/distribution")
  public Map<String, Object> distribution() {
    return Map.of("shards", storage.distribution());
  }

  @GetMapping("/storage/replication")
  public Map<String, Object> replication() {
    var sets = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 3; i++) {
      sets.add(Map.of(
          "name", "rs" + i,
          "members", 2,
          "data_bearing_members", 2,
          "status", "configured"));
    }
    return Map.of("replica_sets", sets);
  }

  @GetMapping("/system/overview")
  public Map<String, Object> overview() {
    return Map.of(
        "api", health(),
        "storage", storageStatus(),
        "rabbitmq", queueHealth(),
        "stats", stats());
  }

  @PostMapping("/internal/nodes/register")
  public Map<String, Object> register(@Valid @RequestBody NodeHeartbeat payload) {
    return storage.registerNode(payload.node_id(), payload.shard_id(), payload.role());
  }

  @PostMapping("/internal/nodes/heartbeat")
  public Map<String, Object> heartbeat(@Valid @RequestBody NodeHeartbeat payload) {
    return storage.registerNode(payload.node_id(), payload.shard_id(), payload.role());
  }

  @PutMapping("/internal/data/{key}")
  public Map<String, Object> internalPut(
      @PathVariable String key,
      @Valid @RequestBody InternalData payload) {
    return storage.put(key, payload.kind(), payload.document());
  }

  @GetMapping("/internal/data/{key}")
  public Map<String, Object> internalGet(
      @PathVariable String key,
      @RequestParam String kind) {
    Map<String, Object> item = storage.get(key, kind);
    if (item == null) {
      throw new NotFound("Data not found");
    }
    return item;
  }

  @DeleteMapping("/internal/data/{key}")
  public Map<String, Boolean> internalDelete(
      @PathVariable String key,
      @RequestParam String kind) {
    return Map.of("deleted", storage.delete(key, kind));
  }

  private Map<String, Object> queueHealth() {
    try {
      Integer count = rabbit.execute(
          channel -> channel.queueDeclarePassive(MessagingConfiguration.QUEUE).getMessageCount());
      return Map.<String, Object>of(
          "connected", true,
          "queue", MessagingConfiguration.QUEUE,
          "ready", count);
    } catch (Exception error) {
      return Map.<String, Object>of(
          "connected", false,
          "queue", MessagingConfiguration.QUEUE,
          "ready", 0,
          "error", error.getMessage() == null ? "RabbitMQ unavailable" : error.getMessage());
    }
  }
}
