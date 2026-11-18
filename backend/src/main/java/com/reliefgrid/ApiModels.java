package com.reliefgrid;

import jakarta.validation.constraints.*;
import java.util.Map;

public final class ApiModels {
  private ApiModels() {}
  public record RequestCreate(
      @NotBlank @Size(min=2,max=160) String location,
      @DecimalMin("-90") @DecimalMax("90") double latitude,
      @DecimalMin("-180") @DecimalMax("180") double longitude,
      @NotBlank @Pattern(regexp="food|water|medicine|transportation|shelter") String request_type,
      @NotBlank @Size(min=3,max=2000) String description,
      @Min(1) @Max(100000) int quantity,
      @Min(1) @Max(5) Integer urgency_level) {}
  public record ResourceCreate(
      @NotBlank @Size(min=2,max=160) String location,
      @DecimalMin("-90") @DecimalMax("90") double latitude,
      @DecimalMin("-180") @DecimalMax("180") double longitude,
      @NotBlank @Pattern(regexp="food|water|medicine|transportation|shelter") String resource_type,
      @NotBlank @Size(min=2,max=160) String provider_name,
      @NotBlank @Size(min=2,max=2000) String description,
      @Min(1) @Max(100000) int available_quantity,
      @NotBlank @Size(min=3,max=300) String contact_information) {}
  public record StatusUpdate(@NotBlank String status) {}
  public record NodeHeartbeat(@NotBlank @Size(min=2,max=100) String node_id, @Min(0) @Max(2) int shard_id, @NotBlank String role) {}
  public record InternalData(@NotBlank String kind, @NotNull Map<String,Object> document) {}
}
