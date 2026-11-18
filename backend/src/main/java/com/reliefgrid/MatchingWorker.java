package com.reliefgrid;

import java.util.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class MatchingWorker {
  private final StorageCoordinator storage;
  private final MatchingService matching;

  MatchingWorker(StorageCoordinator storage, MatchingService matching) {
    this.storage = storage;
    this.matching = matching;
  }

  @RabbitListener(queues = MessagingConfiguration.QUEUE)
  public void handle(Map<String, Object> event) {
    String type = String.valueOf(event.getOrDefault("event_type", "request.created"));
    if ("request.created".equals(type)) {
      Map<String, Object> request = storage.get(String.valueOf(event.get("request_id")), "requests");
      if (request != null
          && !Set.of("MATCHED", "ASSIGNED", "COMPLETED", "CANCELLED").contains(String.valueOf(request.get("status"))))
        matching.match(request);
      return;
    }
    if ("resource.created".equals(type)) {
      Map<String, Object> resource = storage.get(String.valueOf(event.get("resource_id")), "resources");
      if (resource == null)
        return;
      List<Map<String, Object>> requests = storage.list("requests",
          Map.of("request_type", resource.get("resource_type")));
      Collections.reverse(requests);
      for (Map<String, Object> request : requests)
        if (Set.of("PENDING", "UNMATCHED").contains(String.valueOf(request.get("status"))))
          matching.match(request);
      return;
    }
    throw new IllegalArgumentException("Unsupported matching event: " + type);
  }
}
