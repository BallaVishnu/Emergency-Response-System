package com.reliefgrid;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class MatchingWorker {
  private static final Logger log = LoggerFactory.getLogger(MatchingWorker.class);

  private final StorageCoordinator storage;
  private final MatchingService matching;

  @Value("${app.matching.recovery-timeout-seconds:120}")
  private long recoveryTimeoutSeconds;

  MatchingWorker(StorageCoordinator storage, MatchingService matching) {
    this.storage = storage;
    this.matching = matching;
  }

  @RabbitListener(queues = MessagingConfiguration.QUEUE)
  public void handle(Map<String, Object> event) {
    String type = String.valueOf(event.getOrDefault("event_type", "request.created"));

    if ("request.created".equals(type)) {
      Map<String, Object> request = storage.get(
          String.valueOf(event.get("request_id")),
          "requests");

      if (request != null
          && !Set.of("MATCHED", "ASSIGNED", "COMPLETED", "CANCELLED")
              .contains(String.valueOf(request.get("status")))) {
        matching.match(request);
      }
      return;
    }

    if ("resource.created".equals(type)) {
      Map<String, Object> resource = storage.get(
          String.valueOf(event.get("resource_id")),
          "resources");

      if (resource == null) {
        return;
      }

      List<Map<String, Object>> requests = storage.list(
          "requests",
          Map.of("request_type", resource.get("resource_type")));
      Collections.reverse(requests);

      for (Map<String, Object> request : requests) {
        if ("PENDING".equals(String.valueOf(request.get("status")))) {
          matching.match(request);
        }
      }
      return;
    }

    throw new IllegalArgumentException("Unsupported matching event: " + type);
  }

  @Scheduled(fixedDelayString = "${app.matching.recovery-interval-ms:30000}")
  public void recoverStaleMatchingAttempts() {
    try {
      matching.recoverStaleAttempts(Duration.ofSeconds(recoveryTimeoutSeconds));
    } catch (RuntimeException error) {
      log.warn("Matching saga recovery pass failed; it will retry on the next scheduled pass", error);
    }
  }
}
