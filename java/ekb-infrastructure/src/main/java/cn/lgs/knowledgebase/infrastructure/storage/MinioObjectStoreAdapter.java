package cn.lgs.knowledgebase.infrastructure.storage;

import cn.lgs.knowledgebase.application.port.Ports.ObjectInfo;
import cn.lgs.knowledgebase.application.port.Ports.ObjectStore;
import cn.lgs.knowledgebase.application.port.Ports.StoredObject;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class MinioObjectStoreAdapter implements ObjectStore {
  private final MinioClient client;
  private final MinioClient presignClient;
  private final String bucket;

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
    this.client =
        MinioClient.builder()
            .endpoint(normalized)
            .credentials(access, secret)
            .region(normalizedRegion)
            .build();
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
      client.putObject(
          PutObjectArgs.builder().bucket(bucket).object(require(key, "object key")).stream(
                  source, size, size < 0 ? 10 * 1024 * 1024 : -1)
              .contentType(
                  contentType == null || contentType.isBlank()
                      ? "application/octet-stream"
                      : contentType)
              .build());
    } catch (Exception e) {
      throw new IllegalStateException("minio put failed: " + key, e);
    }
  }

  @Override
  public StoredObject get(String key) {
    try {
      StatObjectResponse stat =
          client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
      InputStream stream =
          client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
      return new StoredObject(stream, stat.size(), stat.contentType());
    } catch (Exception e) {
      throw new IllegalStateException("minio get failed: " + key, e);
    }
  }

  @Override
  public ObjectInfo stat(String key) {
    try {
      StatObjectResponse stat =
          client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
      return new ObjectInfo(key, stat.size(), stat.contentType(), stat.etag());
    } catch (Exception e) {
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
