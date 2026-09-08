package cn.lgs.knowledgebase.infrastructure.storage;

import cn.lgs.knowledgebase.application.port.ExternalCallBudget;
import cn.lgs.knowledgebase.application.port.Ports.ObjectInfo;
import cn.lgs.knowledgebase.application.port.Ports.ObjectStore;
import cn.lgs.knowledgebase.application.port.Ports.StoredObject;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import okhttp3.OkHttpClient;

public final class MinioObjectStoreAdapter implements ObjectStore {
  private final MinioClient client;
  private final MinioClient presignClient;
  private final String bucket;
  private final OkHttpClient http;
  private final Function<OkHttpClient, MinioClient> scopedClient;

  public MinioObjectStoreAdapter(
      String endpoint, String accessKey, String secretKey, String bucket) {
    this(endpoint, endpoint, accessKey, secretKey, bucket, "us-east-1");
  }

  public MinioObjectStoreAdapter(
      String endpoint, String publicEndpoint, String accessKey, String secretKey, String bucket) {
    this(endpoint, publicEndpoint, accessKey, secretKey, bucket, "us-east-1");
  }

  public MinioObjectStoreAdapter(
      String endpoint,
      String publicEndpoint,
      String accessKey,
      String secretKey,
      String bucket,
      String region) {
    String normalized = normalizeEndpoint(endpoint);
    String normalizedPublic =
        publicEndpoint == null || publicEndpoint.isBlank()
            ? normalized
            : normalizeEndpoint(publicEndpoint);
    String access = require(accessKey, "MinIO access key");
    String secret = require(secretKey, "MinIO secret key");
    String normalizedRegion = region == null || region.isBlank() ? "us-east-1" : region.trim();
    // The pinned 8.5.17 SDK uses these same five-minute per-I/O defaults.
    // newBuilder shares the pool/dispatcher; per-attempt call deadlines never mutate a shared
    // client.
    long normalTimeout = Duration.ofMinutes(5).toMillis();
    this.http =
        io.minio.http.HttpUtils.newDefaultHttpClient(normalTimeout, normalTimeout, normalTimeout);
    this.scopedClient =
        transport ->
            MinioClient.builder()
                .endpoint(normalized)
                .credentials(access, secret)
                .region(normalizedRegion)
                .httpClient(transport)
                .build();
    this.client = scopedClient.apply(http);
    this.presignClient =
        normalizedPublic.equals(normalized)
            ? this.client
            : MinioClient.builder()
                .endpoint(normalizedPublic)
                .credentials(access, secret)
                .region(normalizedRegion)
                .build();
    this.bucket = require(bucket, "MinIO bucket");
    ensureBucket();
  }

  @Override
  public void put(String key, InputStream source, long size, String contentType) {
    try {
      requestClient()
          .putObject(
              PutObjectArgs.builder().bucket(bucket).object(require(key, "object key")).stream(
                      source, size, size < 0 ? 10 * 1024 * 1024 : -1)
                  .contentType(
                      contentType == null || contentType.isBlank()
                          ? "application/octet-stream"
                          : contentType)
                  .build());
      ExternalCallBudget.check();
    } catch (Exception e) {
      ExternalCallBudget.check();
      throw new IllegalStateException("minio put failed: " + key, e);
    }
  }

  @Override
  public StoredObject get(String key) {
    try {
      StatObjectResponse stat =
          requestClient().statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
      InputStream stream =
          requestClient().getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
      Runnable check = ExternalCallBudget.capturedCheck();
      try {
        check.run();
      } catch (RuntimeException failure) {
        try {
          stream.close();
        } catch (IOException close) {
          failure.addSuppressed(close);
        }
        throw failure;
      }
      // OkHttp's callTimeout remains armed until the complete response body is consumed/closed.
      // Headers alone must not turn a stalled body into an unbounded object read.
      return new StoredObject(checkedStream(stream, check), stat.size(), stat.contentType());
    } catch (Exception e) {
      ExternalCallBudget.check();
      throw new IllegalStateException("minio get failed: " + key, e);
    }
  }

  @Override
  public ObjectInfo stat(String key) {
    try {
      StatObjectResponse stat =
          requestClient().statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
      ExternalCallBudget.check();
      return new ObjectInfo(key, stat.size(), stat.contentType(), stat.etag());
    } catch (Exception e) {
      ExternalCallBudget.check();
      throw new IllegalStateException("minio stat failed: " + key, e);
    }
  }

  @Override
  public String presignGet(String key, Duration expiry) {
    try {
      long seconds = expiry == null ? 900 : Math.max(1, expiry.toSeconds());
      return presignClient.getPresignedObjectUrl(
          GetPresignedObjectUrlArgs.builder()
              .bucket(bucket)
              .object(key)
              .method(Method.GET)
              .expiry((int) Math.min(seconds, 7 * 24 * 3600), TimeUnit.SECONDS)
              .build());
    } catch (Exception e) {
      throw new IllegalStateException("minio presign failed: " + key, e);
    }
  }

  @Override
  public void delete(String key) {
    try {
      requestClient()
          .removeObject(
              RemoveObjectArgs.builder().bucket(bucket).object(require(key, "object key")).build());
      ExternalCallBudget.check();
    } catch (Exception e) {
      ExternalCallBudget.check();
      throw new IllegalStateException("minio delete failed: " + key, e);
    }
  }

  private MinioClient requestClient() {
    if (!ExternalCallBudget.active()) return client;
    Duration remaining = ExternalCallBudget.limit(Duration.ofMinutes(5));
    // The transport takes whole milliseconds. Rounding down can cancel a stalled
    // body before the stage deadline and surface a generic I/O error.
    long timeoutMillis = remaining.plusNanos(999_999).toMillis();
    return scopedClient.apply(
        http.newBuilder().callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build());
  }

  private static InputStream checkedStream(InputStream stream, Runnable check) {
    return new FilterInputStream(stream) {
      @Override
      public int read() throws IOException {
        try {
          int value = in.read();
          if (value == -1) check.run();
          return value;
        } catch (IOException failure) {
          check.run();
          throw failure;
        }
      }

      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        try {
          int count = in.read(buffer, offset, length);
          if (count == -1) check.run();
          return count;
        } catch (IOException failure) {
          check.run();
          throw failure;
        }
      }
    };
  }

  private void ensureBucket() {
    try {
      if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
        client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
      }
    } catch (Exception e) {
      throw new IllegalStateException("cannot initialize MinIO bucket " + bucket, e);
    }
  }

  private static String normalizeEndpoint(String value) {
    String normalized = require(value, "MinIO endpoint");
    if (!normalized.startsWith("http://") && !normalized.startsWith("https://"))
      normalized = "http://" + normalized;
    while (normalized.endsWith("/") && normalized.length() > "http://x".length()) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private static String require(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value.trim();
  }
}
