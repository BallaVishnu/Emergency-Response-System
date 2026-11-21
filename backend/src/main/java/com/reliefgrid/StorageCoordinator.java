package com.reliefgrid;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.stereotype.Service;

@Service
public class StorageCoordinator {
  private final AppProperties properties;
  private final List<MongoClient> clients = new ArrayList<>();
  private final List<MongoDatabase> databases = new ArrayList<>();
  private final Map<String, Map<String, Object>> nodes = new ConcurrentHashMap<>();

  StorageCoordinator(AppProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void connect() {
    for (String uri : List.of(
        properties.mongo().shard0Uri(),
        properties.mongo().shard1Uri(),
        properties.mongo().shard2Uri())) {
      MongoClientSettings settings = MongoClientSettings.builder()
          .applyConnectionString(new ConnectionString(uri))
          .writeConcern(WriteConcern.MAJORITY)
          .build();

      MongoClient client = MongoClients.create(settings);

      try {
        client.getDatabase("admin").runCommand(new Document("ping", 1));
      } catch (Exception error) {
        client.close();
        clients.forEach(MongoClient::close);
        throw new IllegalStateException(
            "MongoDB is required but one or more shard replica sets are unavailable",
            error);
      }

      clients.add(client);
      databases.add(client.getDatabase(properties.mongo().db()));
    }
  }

  public void checkReady() {
    clients.forEach(client -> client.getDatabase("admin").runCommand(new Document("ping", 1)));
  }

  public static int shardFor(String id) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256")
          .digest(id.getBytes(StandardCharsets.UTF_8));
      return new java.math.BigInteger(1, hash)
          .mod(java.math.BigInteger.valueOf(3))
          .intValue();
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private MongoCollection<Document> collection(String id, String kind) {
    return databases.get(shardFor(id)).getCollection(kind);
  }

  private static Map<String, Object> map(Document source) {
    if (source == null) {
      return null;
    }

    var copy = new LinkedHashMap<String, Object>(source);
    copy.remove("_id");
    return copy;
  }

  private static Document document(Map<String, Object> value, String id) {
    var result = new Document(value);
    result.put("_id", id);
    return result;
  }

  public Map<String, Object> put(String id, String kind, Map<String, Object> value) {
    collection(id, kind).replaceOne(
        Filters.eq("_id", id),
        document(value, id),
        new ReplaceOptions().upsert(true));
    return new LinkedHashMap<>(value);
  }

  public Map<String, Object> get(String id, String kind) {
    return map(collection(id, kind).find(Filters.eq("_id", id)).first());
  }

  public boolean delete(String id, String kind) {
    return collection(id, kind)
        .deleteOne(Filters.eq("_id", id))
        .getDeletedCount() == 1;
  }

  public List<Map<String, Object>> list(String kind, Map<String, Object> filters) {
    List<Bson> criteria = new ArrayList<>();

    filters.forEach((key, value) -> {
      if (value != null) {
        criteria.add(Filters.eq(key, value));
      }
    });

    Bson query = criteria.isEmpty() ? new Document() : Filters.and(criteria);
    List<Map<String, Object>> values = new ArrayList<>();

    for (MongoDatabase database : databases) {
      database.getCollection(kind)
          .find(query)
          .sort(Sorts.descending("created_at"))
          .forEach(document -> values.add(map(document)));
    }

    values.sort((first, second) ->
        String.valueOf(second.get("created_at")).compareTo(
            String.valueOf(first.get("created_at"))));
    return values;
  }

  public Map<String, Object> reserveResource(
      String resourceId,
      String requestId,
      int quantity) {
    return reserveResource(
        resourceId,
        requestId,
        UUID.randomUUID().toString(),
        quantity);
  }

  public Map<String, Object> claimRequest(String requestId, String attemptId) {
    Bson query = Filters.and(
        Filters.eq("_id", requestId),
        Filters.eq("status", "PENDING"),
        Filters.eq("assigned_resource_id", null));

    Bson update = Updates.combine(
        Updates.set("status", "PROCESSING"),
        Updates.set("match_attempt_id", attemptId),
        Updates.set("match_attempt_started_at", Instant.now().toString()),
        Updates.set("updated_at", Instant.now().toString()));

    return map(collection(requestId, "requests").findOneAndUpdate(
        query,
        update,
        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)));
  }

  public Map<String, Object> reserveResource(
      String resourceId,
      String requestId,
      String attemptId,
      int quantity) {
    Bson query = Filters.and(
        Filters.eq("_id", resourceId),
        Filters.eq("availability_status", "AVAILABLE"),
        Filters.gte("available_quantity", quantity));

    Bson update = Updates.combine(
        Updates.set("availability_status", "ASSIGNED"),
        Updates.set("assigned_request_id", requestId),
        Updates.set("match_attempt_id", attemptId),
        Updates.set("reserved_quantity", quantity),
        Updates.set("updated_at", Instant.now().toString()),
        Updates.inc("available_quantity", -quantity));

    return map(collection(resourceId, "resources").findOneAndUpdate(
        query,
        update,
        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)));
  }

  public Map<String, Object> finalizeMatch(
      String requestId,
      String attemptId,
      String resourceId) {
    Bson query = Filters.and(
        Filters.eq("_id", requestId),
        Filters.eq("status", "PROCESSING"),
        Filters.eq("match_attempt_id", attemptId));

    Bson update = Updates.combine(
        Updates.set("status", "MATCHED"),
        Updates.set("assigned_resource_id", resourceId),
        Updates.set("updated_at", Instant.now().toString()),
        Updates.unset("match_attempt_id"),
        Updates.unset("match_attempt_started_at"));

    return map(collection(requestId, "requests").findOneAndUpdate(
        query,
        update,
        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)));
  }

  public boolean markPending(String requestId, String attemptId) {
    Bson query = Filters.and(
        Filters.eq("_id", requestId),
        Filters.eq("status", "PROCESSING"),
        Filters.eq("match_attempt_id", attemptId));

    Bson update = Updates.combine(
        Updates.set("status", "PENDING"),
        Updates.set("updated_at", Instant.now().toString()),
        Updates.unset("match_attempt_id"),
        Updates.unset("match_attempt_started_at"));

    return collection(requestId, "requests")
        .updateOne(query, update)
        .getModifiedCount() == 1;
  }

  public boolean releaseReservation(
      String resourceId,
      String requestId,
      String attemptId) {
    MongoCollection<Document> resources = collection(resourceId, "resources");

    Document resource = resources.find(Filters.and(
        Filters.eq("_id", resourceId),
        Filters.eq("assigned_request_id", requestId),
        Filters.eq("match_attempt_id", attemptId))).first();

    if (resource == null) {
      return false;
    }

    int quantity = resource.getInteger("reserved_quantity", 0);
    if (quantity < 1) {
      return false;
    }

    Bson query = Filters.and(
        Filters.eq("_id", resourceId),
        Filters.eq("assigned_request_id", requestId),
        Filters.eq("match_attempt_id", attemptId));

    Bson update = Updates.combine(
        Updates.set("availability_status", "AVAILABLE"),
        Updates.set("updated_at", Instant.now().toString()),
        Updates.inc("available_quantity", quantity),
        Updates.unset("assigned_request_id"),
        Updates.unset("match_attempt_id"),
        Updates.unset("reserved_quantity"));

    return resources.updateOne(query, update).getModifiedCount() == 1;
  }

  public void finishReservation(
      String resourceId,
      String requestId,
      String attemptId) {
    Bson query = Filters.and(
        Filters.eq("_id", resourceId),
        Filters.eq("assigned_request_id", requestId),
        Filters.eq("match_attempt_id", attemptId));

    Bson update = Updates.combine(
        Updates.unset("match_attempt_id"),
        Updates.unset("reserved_quantity"),
        Updates.set("updated_at", Instant.now().toString()));

    collection(resourceId, "resources").updateOne(query, update);
  }

  public boolean abandonStaleAttempt(
      String requestId,
      String attemptId,
      Instant cutoff) {
    Bson query = Filters.and(
        Filters.eq("_id", requestId),
        Filters.eq("status", "PROCESSING"),
        Filters.eq("match_attempt_id", attemptId),
        Filters.lte("match_attempt_started_at", cutoff.toString()));

    Bson update = Updates.combine(
        Updates.set("status", "PENDING"),
        Updates.set("updated_at", Instant.now().toString()),
        Updates.unset("match_attempt_id"),
        Updates.unset("match_attempt_started_at"));

    return collection(requestId, "requests")
        .updateOne(query, update)
        .getModifiedCount() == 1;
  }

  public void reconcileReservations() {
    for (MongoDatabase database : databases) {
      for (Document resource : database.getCollection("resources")
          .find(Filters.exists("match_attempt_id"))) {
        String requestId = resource.getString("assigned_request_id");
        String attemptId = resource.getString("match_attempt_id");
        String resourceId = resource.getString("resource_id");

        if (requestId == null || attemptId == null || resourceId == null) {
          continue;
        }

        Map<String, Object> request = get(requestId, "requests");

        if (request != null
            && "MATCHED".equals(request.get("status"))
            && resourceId.equals(request.get("assigned_resource_id"))) {
          finishReservation(resourceId, requestId, attemptId);
        } else if (request == null
            || !"PROCESSING".equals(request.get("status"))
            || !attemptId.equals(request.get("match_attempt_id"))) {
          releaseReservation(resourceId, requestId, attemptId);
        }
      }
    }
  }

  public Map<String, Object> updateStatus(
      String id,
      String kind,
      String status) {
    Map<String, Object> item = get(id, kind);
    if (item == null) {
      return null;
    }

    item.put(kind.equals("requests") ? "status" : "availability_status", status);
    item.put("updated_at", Instant.now().toString());
    return put(id, kind, item);
  }

  public Map<String, Object> stats() {
    List<Map<String, Object>> requests = list("requests", Map.of());
    List<Map<String, Object>> resources = list("resources", Map.of());

    return Map.of(
        "requests", requests.size(),
        "resources", resources.size(),
        "requests_by_status", counts(
            requests,
            "status",
            List.of("PENDING", "PROCESSING", "MATCHED", "ASSIGNED", "COMPLETED", "CANCELLED")),
        "resources_by_status", counts(
            resources,
            "availability_status",
            List.of("AVAILABLE", "RESERVED", "ASSIGNED", "UNAVAILABLE")));
  }

  private Map<String, Long> counts(
      List<Map<String, Object>> values,
      String key,
      List<String> statuses) {
    var result = new LinkedHashMap<String, Long>();

    for (String status : statuses) {
      result.put(status, values.stream()
          .filter(value -> status.equals(value.get(key)))
          .count());
    }

    return result;
  }

  public List<Map<String, Object>> distribution() {
    var result = new ArrayList<Map<String, Object>>();

    for (int index = 0; index < 3; index++) {
      long count = databases.get(index).getCollection("requests").countDocuments()
          + databases.get(index).getCollection("resources").countDocuments();

      result.add(Map.of(
          "shard_id", index,
          "record_count", count,
          "healthy", true,
          "replica_set", "rs" + index));
    }

    return result;
  }

  public List<Map<String, Object>> storageStatus() {
    String now = Instant.now().toString();

    for (int i = 0; i < 3; i++) {
      registerIfAbsent("shard-" + i + "-primary", i, "PRIMARY", now);
      registerIfAbsent("shard-" + i + "-secondary", i, "SECONDARY", now);
    }

    return nodes.values().stream()
        .<Map<String, Object>>map(node -> {
          var copy = new LinkedHashMap<String, Object>(node);
          copy.put("health", "HEALTHY");
          copy.put("replica_set", "rs" + node.get("shard_id"));
          return copy;
        })
        .toList();
  }

  private void registerIfAbsent(
      String id,
      int shard,
      String role,
      String now) {
    nodes.putIfAbsent(id, new LinkedHashMap<>(Map.of(
        "node_id", id,
        "shard_id", shard,
        "role", role,
        "last_heartbeat", now)));
  }

  public Map<String, Object> registerNode(
      String id,
      int shard,
      String role) {
    var node = new LinkedHashMap<String, Object>(Map.of(
        "node_id", id,
        "shard_id", shard,
        "role", role,
        "last_heartbeat", Instant.now().toString()));

    nodes.put(id, node);
    node.put("health", "HEALTHY");
    node.put("replica_set", "rs" + shard);
    return node;
  }
}
