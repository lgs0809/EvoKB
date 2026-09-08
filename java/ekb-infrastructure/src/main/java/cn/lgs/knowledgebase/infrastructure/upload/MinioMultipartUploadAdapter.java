package cn.lgs.knowledgebase.infrastructure.upload;

import static cn.lgs.knowledgebase.application.upload.MultipartUploadContracts.*;

import com.google.common.collect.HashMultimap;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioAsyncClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** MinIO 8.5.17's public native async multipart APIs, without a parallel S3 implementation. */
public final class MinioMultipartUploadAdapter implements Store, AutoCloseable {
  private final MinioAsyncClient client;
  private final MinioAsyncClient presigner;
  private final okhttp3.OkHttpClient http;
  private final String bucket;
  private final String region;

  public MinioMultipartUploadAdapter(
      String endpoint,
      String publicEndpoint,
      String access,
      String secret,
      String bucket,
      String region) {
    this.bucket = bucket;
    this.region = region;
    http =
        new okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(125, TimeUnit.SECONDS)
            .build();
    client =
        MinioAsyncClient.builder()
            .endpoint(endpoint)
            .credentials(access, secret)
            .region(region)
            .httpClient(http)
            .build();
    presigner =
        MinioAsyncClient.builder()
            .endpoint(publicEndpoint)
            .credentials(access, secret)
            .region(region)
            .httpClient(http)
            .build();
  }

  public String bucket() {
    return bucket;
  }

  public String create(String key, String filename, String session) {
    var headers = HashMultimap.<String, String>create();
    headers.put("x-amz-meta-evokb-session-id", session);
    headers.put("Content-Type", "application/octet-stream");
    try {
      return await(client.createMultipartUploadAsync(bucket, region, key, headers, null))
          .result()
          .uploadId();
    } catch (Exception e) {
      throw failure("create", e);
    }
  }

  public List<Part> list(Session session) {
    require(session);
    List<Part> out = new ArrayList<>();
    int marker = 0;
    try {
      for (int page = 0; page < 100; page++) {
        var value =
            await(
                    client.listPartsAsync(
                        bucket,
                        region,
                        session.objectKey(),
                        100,
                        marker,
                        session.uploadId(),
                        null,
                        null))
                .result();
        for (var part : value.partList())
          out.add(new Part(part.partNumber(), part.partSize(), part.etag()));
        if (!value.isTruncated()) return List.copyOf(out);
        if (value.nextPartNumberMarker() <= marker)
          throw new IllegalStateException("multipart cursor did not advance");
        marker = value.nextPartNumberMarker();
      }
      throw new IllegalStateException("multipart listing exceeds bounded pagination");
    } catch (Exception e) {
      throw failure("list parts", e);
    }
  }

  public String presign(Session session, int number, String md5) {
    require(session);
    try {
      long length =
          number == session.partCount()
              ? session.sizeBytes() - (long) (number - 1) * session.partSize()
              : session.partSize();
      return presigner.getPresignedObjectUrl(
          GetPresignedObjectUrlArgs.builder()
              .bucket(bucket)
              .object(session.objectKey())
              .method(Method.PUT)
              .expiry(URL_SECONDS)
              .extraQueryParams(
                  Map.of("uploadId", session.uploadId(), "partNumber", Integer.toString(number)))
              .extraHeaders(Map.of("Content-MD5", md5, "Content-Length", Long.toString(length)))
              .build());
    } catch (Exception e) {
      throw failure("presign part", e);
    }
  }

  public ObjectIdentity complete(Session session) {
    require(session);
    try {
      var parts =
          session.frozenParts().stream()
              .map(part -> new io.minio.messages.Part(part.number(), part.etag()))
              .toArray(io.minio.messages.Part[]::new);
      await(
          client.completeMultipartUploadAsync(
              bucket, region, session.objectKey(), session.uploadId(), parts, null, null));
      return head(session.objectKey())
          .orElseThrow(() -> new IllegalStateException("completed object is not observable"));
    } catch (Exception e) {
      throw failure("complete", e);
    }
  }

  public Optional<ObjectIdentity> head(String key) {
    try {
      var value =
          await(client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()));
      return Optional.of(
          new ObjectIdentity(
              value.size(),
              value.etag(),
              value.versionId() == null ? "" : value.versionId(),
              value.userMetadata()));
    } catch (Exception e) {
      Throwable cause = deep(e);
      if (cause instanceof ErrorResponseException s3 && missingObject(s3.errorResponse().code()))
        return Optional.empty();
      throw failure("head", e);
    }
  }

  public void abort(Session session) {
    require(session);
    try {
      await(
          client.abortMultipartUploadAsync(
              bucket, region, session.objectKey(), session.uploadId(), null, null));
    } catch (Exception e) {
      Throwable cause = deep(e);
      if (cause instanceof ErrorResponseException s3
          && "NoSuchUpload".equals(s3.errorResponse().code())) return;
      throw failure("abort", e);
    }
  }

  public List<String> findUploads(String key) {
    List<String> out = new ArrayList<>();
    String marker = null, idMarker = null;
    try {
      for (int page = 0; page < 100; page++) {
        var value =
            await(
                    client.listMultipartUploadsAsync(
                        bucket, region, null, null, marker, 100, key, idMarker, null, null))
                .result();
        for (var upload : value.uploads())
          if (key.equals(upload.objectName())) out.add(upload.uploadId());
        if (!value.isTruncated()) return List.copyOf(out);
        if (java.util.Objects.equals(marker, value.nextKeyMarker())
            && java.util.Objects.equals(idMarker, value.nextUploadIdMarker()))
          throw new IllegalStateException("multipart upload cursor did not advance");
        marker = value.nextKeyMarker();
        idMarker = value.nextUploadIdMarker();
      }
      throw new IllegalStateException("upload listing exceeds bounded pagination");
    } catch (Exception e) {
      throw failure("reconcile creation", e);
    }
  }

  private void require(Session value) {
    if (!bucket.equals(value.bucket())
        || value.uploadId() == null
        || !value.objectKey().startsWith("upload-sessions/"))
      throw new IllegalArgumentException("invalid owned multipart storage identity");
  }

  private static boolean missingObject(String code) {
    return "NoSuchKey".equals(code) || "NoSuchObject".equals(code);
  }

  private static <T> T await(CompletableFuture<T> future) throws Exception {
    try {
      return future.get(130, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw interrupted;
    }
  }

  private static Throwable deep(Throwable value) {
    while (value.getCause() != null
        && (value instanceof java.util.concurrent.ExecutionException
            || value instanceof java.util.concurrent.CompletionException)) value = value.getCause();
    return value;
  }

  private static IllegalStateException failure(String operation, Exception error) {
    return new IllegalStateException("multipart storage " + operation + " failed", deep(error));
  }

  public void close() {
    http.dispatcher().executorService().shutdown();
    http.connectionPool().evictAll();
  }
}
