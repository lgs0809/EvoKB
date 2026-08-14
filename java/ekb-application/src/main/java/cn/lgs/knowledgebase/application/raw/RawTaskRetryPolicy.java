package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.application.port.Ports.TransientDependencyFailure;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Transport retries and durable business attempts have distinct budgets. Bad inputs never spin. */
public final class RawTaskRetryPolicy {
  private RawTaskRetryPolicy() {}

  public static String code(Throwable failure, String fallback) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
      if (cause instanceof RawIngestionApplicationService.RawIngestFailure raw) return raw.code();
      String message = cause.getMessage() == null ? "" : cause.getMessage();
      for (String code :
          java.util.List.of(
              "INPUT_ERROR",
              "QUALITY_REVIEW_REQUIRED",
              "TOKENIZER_UNAVAILABLE",
              "ENCODING_PROFILE_MISMATCH",
              "PARSER_PROFILE_INVALID",
              "PARSER_VERSION_MISMATCH",
              "RAW_SOURCE_REVOKED",
              "RAW_STAGE_EXPIRED",
              "RAW_STALE_CLAIM",
              "RAW_SOURCE_BUSY",
              "PARSER_STAGE_TIMEOUT",
              "PARSER_PROCESS_INTERRUPTED",
              "PARSER_WORKER_LOST",
              "TASK_PERSIST_FAILED",
              "STAGING_FILE_MISSING")) if (message.contains(code)) return code;
    }
    return fallback;
  }

  public static boolean retryable(String code, Throwable failure) {
    if (Set.of(
            "INPUT_ERROR",
            "QUALITY_REVIEW_REQUIRED",
            "TOKENIZER_UNAVAILABLE",
            "ENCODING_PROFILE_MISMATCH",
            "PARSER_PROFILE_INVALID",
            "PARSER_VERSION_MISMATCH",
            "RAW_SOURCE_REVOKED",
            "STAGING_FILE_MISSING")
        .contains(code)) return false;
    if (Set.of(
            "RAW_STAGE_EXPIRED",
            "RAW_STALE_CLAIM",
            "RAW_SOURCE_BUSY",
            "PARSER_STAGE_TIMEOUT",
            "PARSER_PROCESS_INTERRUPTED",
            "PARSER_WORKER_LOST",
            "TASK_PERSIST_FAILED")
        .contains(code)) return true;
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
      if (cause instanceof TransientDependencyFailure
          || cause instanceof IOException
          || cause instanceof java.util.concurrent.TimeoutException) return true;
      String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase();
      if (message.contains("retrieval_busy")
          || message.contains("503")
          || message.contains("502")
          || message.contains("504")
          || message.contains("connection reset")
          || message.contains("timed out")
          || message.contains("connection refused")) return true;
    }
    return false;
  }
}
