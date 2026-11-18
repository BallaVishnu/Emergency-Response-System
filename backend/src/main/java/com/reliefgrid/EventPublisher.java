package com.reliefgrid;

import java.util.Map;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class EventPublisher {
  private final RabbitTemplate rabbit;

  EventPublisher(RabbitTemplate rabbit) {
    this.rabbit = rabbit;
  }

  public void requestCreated(String id) {
    publish("request.created", Map.of("event_type", "request.created", "request_id", id));
  }

  public void resourceCreated(String id) {
    publish("resource.created", Map.of("event_type", "resource.created", "resource_id", id));
  }

  private void publish(String routingKey, Map<String, String> event) {
    rabbit.convertAndSend(MessagingConfiguration.EXCHANGE, routingKey, event);
  }
}
