package com.reliefgrid;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String role, Mongo mongo, double matchingRadiusKm, int heartbeatTimeoutSeconds) {
  public record Mongo(String db, String shard0Uri, String shard1Uri, String shard2Uri) {}
}
