package com.reliefgrid;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class StorageCoordinatorTest {
  @Test void shardSelectionIsDeterministicAndBounded() {
    int shard = StorageCoordinator.shardFor("req-123");
    assertEquals(shard, StorageCoordinator.shardFor("req-123"));
    assertTrue(shard >= 0 && shard < 3);
  }
}
