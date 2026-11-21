package com.reliefgrid;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StorageCoordinatorTest {
  @Test
  void shardSelectionIsDeterministicAndBounded() {
    int shard = StorageCoordinator.shardFor("req-123");
    assertEquals(shard, StorageCoordinator.shardFor("req-123"));
    assertTrue(shard >= 0 && shard < 3);
  }

  @Test
  void similarIdsCanRouteToDifferentShards() {
    long distinctShards = java.util.stream.IntStream.range(0, 100)
        .mapToObj(index -> "req-" + index)
        .map(StorageCoordinator::shardFor)
        .distinct()
        .count();

    assertTrue(distinctShards >= 2, "IDs should be distributed across the three shards");
  }

  @Test
  void shardSelectionHandlesEmptyAndSpecialCharacterIds() {
    assertTrue(StorageCoordinator.shardFor("") >= 0);
    assertTrue(StorageCoordinator.shardFor("") < 3);
    assertTrue(StorageCoordinator.shardFor("resource/with spaces & symbols") >= 0);
    assertTrue(StorageCoordinator.shardFor("resource/with spaces & symbols") < 3);
  }
}
