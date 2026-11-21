package com.reliefgrid;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EmergencyController.class)
class EmergencyControllerTest {
  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private StorageCoordinator storage;
  @MockitoBean
  private EventPublisher events;
  @MockitoBean
  private MatchingService matching;
  @MockitoBean
  private RabbitTemplate rabbit;

  @BeforeEach
  void setUp() {
    reset(storage, events, matching, rabbit);
  }

  @Test
  void createRequestReturnsCreatedAndPublishesEvent() throws Exception {
    when(storage.put(anyString(), eq("requests"), anyMap())).thenAnswer(invocation -> invocation.getArgument(2));

    mvc.perform(post("/requests")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"location":"Central Camp","latitude":13.02,"longitude":77.55,
                 "request_type":"water","description":"Clean drinking water","quantity":20,"urgency_level":4}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.request_id").exists())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.assigned_resource_id").doesNotExist())
        .andExpect(jsonPath("$.created_at").exists());

    verify(storage).put(anyString(), eq("requests"), anyMap());
    verify(events).requestCreated(anyString());
  }

  @Test
  void createRequestRejectsInvalidTypeAndQuantity() throws Exception {
    mvc.perform(post("/requests")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"location":"X","latitude":100,"longitude":77,
                 "request_type":"unknown","description":"x","quantity":0}
                """))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").isArray());

    verifyNoInteractions(events);
  }

  @Test
  void getRequestReturnsStoredDocument() throws Exception {
    when(storage.get("req-1", "requests")).thenReturn(Map.of(
        "request_id", "req-1", "request_type", "water", "status", "PENDING"));

    mvc.perform(get("/requests/req-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.request_id").value("req-1"))
        .andExpect(jsonPath("$.request_type").value("water"));
  }

  @Test
  void getRequestReturnsNotFound() throws Exception {
    when(storage.get("missing", "requests")).thenReturn(null);

    mvc.perform(get("/requests/missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("Request not found"));
  }

  @Test
  void updateRequestStatusRejectsUnknownStatus() throws Exception {
    mvc.perform(put("/requests/req-1/status")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"UNKNOWN\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updateRequestStatusUpdatesStoredRequest() throws Exception {
    when(storage.get("req-1", "requests")).thenReturn(Map.of("request_id", "req-1", "status", "PENDING"));
    when(storage.updateStatus("req-1", "requests", "COMPLETED"))
        .thenReturn(Map.of("request_id", "req-1", "status", "COMPLETED"));

    mvc.perform(put("/requests/req-1/status")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"COMPLETED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));
  }

  @Test
  void createResourceReturnsCreatedAndPublishesEvent() throws Exception {
    when(storage.put(anyString(), eq("resources"), anyMap())).thenAnswer(invocation -> invocation.getArgument(2));

    mvc.perform(post("/resources")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"location":"North Depot","latitude":13.10,"longitude":77.60,
                 "resource_type":"water","provider_name":"Community NGO","description":"Drinking water supply",
                 "available_quantity":100,"contact_information":"help@example.org"}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.resource_id").exists())
        .andExpect(jsonPath("$.availability_status").value("AVAILABLE"));

    verify(events).resourceCreated(anyString());
  }

  @Test
  void listRequestsAppliesOptionalFilters() throws Exception {
    when(storage.list(eq("requests"), eq(Map.of("request_type", "water", "status", "PENDING"))))
        .thenReturn(List.of(Map.of("request_id", "req-1", "request_type", "water", "status", "PENDING")));

    mvc.perform(get("/requests").param("request_type", "water").param("status", "PENDING").param("limit", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].request_id").value("req-1"));
  }

  @Test
  void matchEndpointDelegatesToMatchingService() throws Exception {
    Map<String, Object> request = Map.of("request_id", "req-1", "status", "PENDING");
    Map<String, Object> result = Map.of("request_id", "req-1", "status", "MATCHED", "resource_id", "res-1");
    when(storage.get("req-1", "requests")).thenReturn(request);
    when(matching.match(request)).thenReturn(result);

    mvc.perform(post("/match/req-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("MATCHED"))
        .andExpect(jsonPath("$.resource_id").value("res-1"));
  }

  @Test
  void statsAndDistributionEndpointsExposeMonitoringData() throws Exception {
    when(storage.stats()).thenReturn(Map.of("requests", 2, "resources", 3));
    when(storage.distribution()).thenReturn(List.of(
        Map.of("shard_id", 0, "record_count", 2, "healthy", true)));

    mvc.perform(get("/stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requests").value(2));

    mvc.perform(get("/storage/distribution"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shards[0].shard_id").value(0));
  }
}
