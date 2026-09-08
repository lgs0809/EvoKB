package cn.lgs.knowledgebase.infrastructure.ingestion;

import cn.lgs.knowledgebase.application.port.Ports.UploadRepository;
import cn.lgs.knowledgebase.application.raw.RawDeliveryQueue;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Framework XREADGROUP/XPENDING/XCLAIM/XACK; idle alone never grants a business claim. */
public final class RedisRawDeliveryQueue implements RawDeliveryQueue {
  private final StringRedisTemplate redis;
  private final UploadRepository tasks;
  private final String stream, group, consumer;
  private String pendingCursor = "";
  private volatile boolean initialized;
  private org.redisson.api.RedissonClient recoveryLocks;

  public RedisRawDeliveryQueue(
      StringRedisTemplate redis,
      UploadRepository tasks,
      String stream,
      String group,
      String consumer) {
    this.redis = java.util.Objects.requireNonNull(redis);
    this.tasks = java.util.Objects.requireNonNull(tasks);
    this.stream = required(stream);
    this.group = required(group);
    this.consumer = required(consumer);
  }

  public RedisRawDeliveryQueue(
      StringRedisTemplate redis,
      UploadRepository tasks,
      String stream,
      String group,
      String consumer,
      org.redisson.api.RedissonClient recoveryLocks) {
    this(redis, tasks, stream, group, consumer);
    this.recoveryLocks = java.util.Objects.requireNonNull(recoveryLocks);
  }

  @Override
  public boolean withRecoveryLease(Runnable action) {
    if (recoveryLocks == null) return RawDeliveryQueue.super.withRecoveryLease(action);
    var lock = recoveryLocks.getLock(stream + ":recovery");
    try {
      if (!lock.tryLock(0, 15, java.util.concurrent.TimeUnit.SECONDS)) return false;
      try {
        action.run();
        return true;
      } finally {
        if (lock.isHeldByCurrentThread()) lock.unlock();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private synchronized void initialize() {
    if (initialized) return;
    try {
      redis.execute(
          (RedisCallback<String>)
              connection ->
                  connection
                      .streamCommands()
                      .xGroupCreate(
                          stream.getBytes(StandardCharsets.UTF_8),
                          group,
                          ReadOffset.from("0-0"),
                          true));
    } catch (RuntimeException error) {
      if (!hasBusyGroup(error)) throw error;
    }
    initialized = true;
  }

  private static boolean hasBusyGroup(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause())
      if (current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) return true;
    return false;
  }

  public String append(String fileId) {
    initialize();
    return redis.opsForStream().add(stream, Map.of("file_id", required(fileId))).getValue();
  }

  public List<Message> read(int limit) {
    initialize();
    var records =
        redis
            .<String, String>opsForStream()
            .read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(Math.max(1, Math.min(100, limit))),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
    return messages(records);
  }

  public synchronized List<Message> recover(int limit) {
    initialize();
    int count = Math.max(1, Math.min(100, limit));
    Range<String> range =
        pendingCursor.isEmpty()
            ? Range.unbounded()
            : Range.of(Range.Bound.exclusive(pendingCursor), Range.Bound.unbounded());
    var pending = redis.opsForStream().pending(stream, group, range, count);
    var result = new ArrayList<Message>();
    for (var item : pending) {
      var records =
          redis
              .<String, String>opsForStream()
              .range(stream, Range.just(item.getIdAsString()), Limit.limit().count(1));
      if (records == null || records.isEmpty()) {
        tasks.rawMissingDelivery(item.getIdAsString());
        // DB commit precedes ACK. A Redis failure leaves the durable fact and retry intention.
        acknowledge(item.getIdAsString());
        continue;
      }
      String id = records.get(0).getValue().get("file_id");
      if (id == null || id.isBlank()) {
        tasks.rawRejectDelivery(item.getIdAsString(), id);
        acknowledge(item.getIdAsString());
        continue;
      }
      if (!tasks.rawRecoverable(id)) continue;
      result.addAll(
          messages(
              redis
                  .<String, String>opsForStream()
                  .claim(stream, group, consumer, Duration.ZERO, item.getId())));
    }
    pendingCursor = pending.size() < count ? "" : pending.get(pending.size() - 1).getIdAsString();
    return List.copyOf(result);
  }

  public void acknowledge(String id) {
    initialize();
    redis.opsForStream().acknowledge(stream, group, id);
  }

  private static List<Message> messages(List<MapRecord<String, String, String>> records) {
    if (records == null) return List.of();
    return records.stream()
        .map(
            record ->
                new Message(
                    record.getId().getValue(), record.getValue().getOrDefault("file_id", "")))
        .toList();
  }

  private static String required(String value) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Redis raw delivery identity required");
    return value;
  }
}
