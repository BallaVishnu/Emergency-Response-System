package com.reliefgrid;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MatchingService {
  private final StorageCoordinator storage; private final AppProperties properties;
  MatchingService(StorageCoordinator storage, AppProperties properties) { this.storage=storage; this.properties=properties; }
  public Map<String,Object> match(Map<String,Object> request) {
    String requestId=(String)request.get("request_id");
    if (request.get("assigned_resource_id") != null) return result(requestId, String.valueOf(request.get("status")), request.get("assigned_resource_id"), null, "Request already assigned");
    List<Map<String,Object>> candidates=storage.list("resources", Map.of("resource_type",request.get("request_type"),"availability_status","AVAILABLE"));
    candidates.sort(Comparator.comparingDouble(candidate -> distance(request,candidate)));
    for (Map<String,Object> candidate : candidates) { double distance=distance(request,candidate); if (distance <= properties.matchingRadiusKm()) { Map<String,Object> reserved=storage.reserveResource((String)candidate.get("resource_id"), requestId, ((Number)request.get("quantity")).intValue()); if (reserved != null) { var updated=new LinkedHashMap<>(request); updated.put("assigned_resource_id",reserved.get("resource_id")); updated.put("status","MATCHED"); updated.put("updated_at",Instant.now().toString()); storage.put(requestId,"requests",updated); return result(requestId,"MATCHED",reserved.get("resource_id"),Math.round(distance*100d)/100d,"Closest available resource assigned"); } } }
    storage.updateStatus(requestId,"requests","UNMATCHED"); return result(requestId,"UNMATCHED",null,null,"No suitable available resource found");
  }
  public Map<String,Object> currentMatch(Map<String,Object> request) { return result((String)request.get("request_id"),String.valueOf(request.get("status")),request.get("assigned_resource_id"),null,request.get("assigned_resource_id") == null ? "No assignment" : "Assignment found"); }
  private Map<String,Object> result(String id,String status,Object resource,Double distance,String message) { var result=new LinkedHashMap<String,Object>(); result.put("request_id",id);result.put("status",status);result.put("resource_id",resource);if(distance!=null)result.put("distance_km",distance);result.put("message",message);return result; }
  private double distance(Map<String,Object> first,Map<String,Object> second) { double lat1=Math.toRadians(((Number)first.get("latitude")).doubleValue()), lon1=Math.toRadians(((Number)first.get("longitude")).doubleValue()), lat2=Math.toRadians(((Number)second.get("latitude")).doubleValue()), lon2=Math.toRadians(((Number)second.get("longitude")).doubleValue()); double value=Math.pow(Math.sin((lat2-lat1)/2),2)+Math.cos(lat1)*Math.cos(lat2)*Math.pow(Math.sin((lon2-lon1)/2),2);return 6371d*2*Math.asin(Math.sqrt(value)); }
}
