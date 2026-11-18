package com.reliefgrid;

import java.util.Map;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.*;

@Configuration
public class MessagingConfiguration {
  public static final String EXCHANGE = "emergency.events", QUEUE = "matching.requests", DLQ = "matching.dead-letter";

  @Bean
  DirectExchange emergencyExchange() {
    return new DirectExchange(EXCHANGE, true, false);
  }

  @Bean
  Queue matchingQueue() {
    return new Queue(QUEUE, true, false, false,
        Map.of("x-dead-letter-exchange", EXCHANGE, "x-dead-letter-routing-key", "request.failed"));
  }

  @Bean
  Queue deadLetterQueue() {
    return new Queue(DLQ, true);
  }

  @Bean
  Binding requestBinding() {
    return BindingBuilder.bind(matchingQueue()).to(emergencyExchange()).with("request.created");
  }

  @Bean
  Binding resourceBinding() {
    return BindingBuilder.bind(matchingQueue()).to(emergencyExchange()).with("resource.created");
  }

  @Bean
  Binding deadLetterBinding() {
    return BindingBuilder.bind(deadLetterQueue()).to(emergencyExchange()).with("request.failed");
  }

  @Bean
  MessageConverter messageConverter() {
    return new Jackson2JsonMessageConverter();
  }

  @Bean
  SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory,
      MessageConverter messageConverter) {
    var factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(messageConverter);
    factory.setDefaultRequeueRejected(false);
    factory.setPrefetchCount(1);
    return factory;
  }
}
