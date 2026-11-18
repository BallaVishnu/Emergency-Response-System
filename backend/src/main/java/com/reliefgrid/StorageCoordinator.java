package com.reliefgrid;

import com.mongodb.client.*;
import com.mongodb.client.model.*;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.stereotype.Service;

@Service
public class StorageCoordinator {
  private final AppProperties properties;
  private final List<MongoClient> clients = new ArrayList<>();
  private final List<MongoDatabase> databases = new ArrayList<>();
  private final Map<String, Map<String,Object>> nodes = new ConcurrentHashMap<>();

  StorageCoordinator(AppProperties properties) { this.properties = properties; }

  @PostConstruct
  void connect() {
    for (String uri : List.of(properties.mongo().shard0Uri(), properties.mongo().shard1Uri(), properties.mongo().shard2Uri())) {
      MongoClient client = MongoClients.create(uri);
      try { client.getDatabase("admin").runCommand(new Document("ping", 1)); }
      catch (Exception error) { client.close(); clients.forEach(MongoClient::close); throw new IllegalStateException("MongoDB is required but one or more shard replica sets are unavailable", error); }
      clients.add(client); databases.add(client.getDatabase(properties.mongo().db()));
    }
  }

  public void checkReady() { clients.forEach(client -> client.getDatabase("admin").runCommand(new Document("ping", 1))); }

  public static int shardFor(String id) {
    try { byte[] hash = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)); return new java.math.BigInteger(1, hash).mod(java.math.BigInteger.valueOf(3)).intValue(); }
    catch (Exception error) { throw new IllegalStateException(error); }
  }

  private MongoCollection<Document> collection(String id, String kind) { return databases.get(shardFor(id)).getCollection(kind); }
  private static Map<String,Object> map(Document source) { if (source == null) return null; var copy = new LinkedHashMap<String,Object>(source); copy.remove("_id"); return copy; }
  private static Document document(Map<String,Object> value, String id) { var result = new Document(value); result.put("_id", id); return result; }

  public Map<String,Object> put(String id, String kind, Map<String,Object> value) {
    collection(id, kind).replaceOne(Filters.eq("_id", id), document(value, id), new ReplaceOptions().upsert(true));
    return new LinkedHashMap<>(value);
  }

  public Map<String,Object> get(String id, String kind) { return map(collection(id, kind).find(Filters.eq("_id", id)).first()); }
  public boolean delete(String id, String kind) { return collection(id, kind).deleteOne(Filters.eq("_id", id)).getDeletedCount() == 1;}

  public List<Map<String,Object>> list(String kind, Map<String,Object> filters) {
    List<Bson> criteria = new ArrayList<>(); filters.forEach((key, value) -> { if (value != null) criteria.add(Filters.eq(key, value)); });
    Bson query = criteria.isEmpty() ? new Document() : Filters.and(criteria);
    List<Map<String,Object>> values = new ArrayList<>();
    for (MongoDatabase database : databases) database.getCollection(kind).find(query).sort(Sorts.descending("created_at")).forEach(document -> values.add(map(document)));
    values.sort((a,b) -> String.valueOf(b.get("created_at")).compareTo(String.valueOf(a.get("created_at"))));
    return values;
  }

  public Map<String,Object> reserveResource(String resourceId, String requestId, int quantity) {
    Bson query = Filters.and(Filters.eq("_id", resourceId), Filters.eq("availability_status", "AVAILABLE"), Filters.gte("available_quantity", quantity));
    Bson update = Updates.combine(Updates.set("availability_status", "ASSIGNED"), Updates.set("assigned_request_id", requestId), Updates.set("updated_at", Instant.now().toString()), Updates.inc("available_quantity", -quantity));
    return map(collection(resourceId, "resources").findOneAndUpdate(query, update, new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)));
  }

  public Map<String,Object> updateStatus(String id, String kind, String status) {
    Map<String,Object> item = get(id, kind); if (item == null) return null;
    item.put(kind.equals("requests") ? "status" : "availability_status", status); item.put("updated_at", Instant.now().toString()); return put(id, kind, item);
  }

  public Map<String,Object> stats() {
    List<Map<String,Object>> requests = list("requests", Map.of()); List<Map<String,Object>> resources = list("resources", Map.of());
    return Map.of("requests", requests.size(), "resources", resources.size(), "requests_by_status", counts(requests, "status", List.of("PENDING","PROCESSING","MATCHED","ASSIGNED","COMPLETED","CANCELLED","UNMATCHED")), "resources_by_status", counts(resources, "availability_status", List.of("AVAILABLE","RESERVED","ASSIGNED","UNAVAILABLE")));
  }

  private Map<String,Long> counts(List<Map<String,Object>> values, String key, List<String> statuses) { var result = new LinkedHashMap<String,Long>(); for (String status : statuses) result.put(status, values.stream().filter(v -> status.equals(v.get(key))).count()); return result; }
  public List<Map<String,Object>> distribution() { var result = new ArrayList<Map<String,Object>>(); for (int index=0; index<3; index++) { long count=databases.get(index).getCollection("requests").countDocuments()+databases.get(index).getCollection("resources").countDocuments(); result.add(Map.of("shard_id",index,"record_count",count,"healthy",true,"replica_set","rs"+index)); } return result; }
  
  public List<Map<String,Object>> storageStatus() {
    String now=Instant.now().toString(); for(int i=0;i<3;i++) { registerIfAbsent("shard-"+i+"-primary",i,"PRIMARY",now); registerIfAbsent("shard-"+i+"-secondary",i,"SECONDARY",now); }
    return nodes.values().stream().<Map<String,Object>>map(node -> { var copy=new LinkedHashMap<String,Object>(node); copy.put("health", "HEALTHY"); copy.put("replica_set", "rs"+node.get("shard_id")); return copy; }).toList();
  }

  private void registerIfAbsent(String id,int shard,String role,String now) { nodes.putIfAbsent(id, new LinkedHashMap<>(Map.of("node_id",id,"shard_id",shard,"role",role,"last_heartbeat",now))); }
  public Map<String,Object> registerNode(String id,int shard,String role) { var node=new LinkedHashMap<String,Object>(Map.of("node_id",id,"shard_id",shard,"role",role,"last_heartbeat",Instant.now().toString())); nodes.put(id,node); node.put("health","HEALTHY"); node.put("replica_set","rs"+shard); return node; }
}
