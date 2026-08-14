package cn.lgs.knowledgebase.application.raw;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class RawTaskRetryPolicyTest {
  @Test
  void malformedInputDoesNotRetryEvenWhenItsParserWrappedAnIoError() {
    var failure =
        new IllegalStateException("INPUT_ERROR: malformed original", new IOException("bad zip"));
    String code = RawTaskRetryPolicy.code(failure, "INGEST_FAILED");
    assertEquals("INPUT_ERROR", code);
    assertFalse(RawTaskRetryPolicy.retryable(code, failure));
  }

  @Test
  void sourceReservationContentionGetsTheDurableAttemptBudget() {
    var failure =
        new IllegalStateException("RAW_SOURCE_BUSY: another live owner reserved this version");
    String code = RawTaskRetryPolicy.code(failure, "INGEST_FAILED");
    assertEquals("RAW_SOURCE_BUSY", code);
    assertTrue(RawTaskRetryPolicy.retryable(code, failure));
  }

  @Test
  void nestedNetworkFailureRemainsRetryableWithoutAProviderBodyMatch() {
    var failure =
        new IllegalStateException("original object unavailable", new IOException("closed channel"));
    assertTrue(RawTaskRetryPolicy.retryable("ORIGINAL_RESTORE_FAILED", failure));
  }

  @Test
  void revokedSourceCannotRetryAnOtherwiseTransientProviderFailure() {
    var failure =
        new IllegalStateException(
            "RAW_SOURCE_REVOKED: current ACL changed", new IOException("connection reset"));
    assertFalse(
        RawTaskRetryPolicy.retryable(RawTaskRetryPolicy.code(failure, "INGEST_FAILED"), failure));
  }

  @Test
  void ordinaryProgrammingFailureDoesNotSpinTheWorker() {
    assertFalse(
        RawTaskRetryPolicy.retryable(
            "INGEST_FAILED", new IllegalArgumentException("invalid internal state")));
  }

  @Test
  void cyclicThirdPartyCauseCannotTrapBackgroundFailureClassification() {
    var failure =
        new RuntimeException("unknown failure") {
          @Override
          public synchronized Throwable getCause() {
            return this;
          }
        };
    assertEquals("INGEST_FAILED", RawTaskRetryPolicy.code(failure, "INGEST_FAILED"));
    assertFalse(RawTaskRetryPolicy.retryable("INGEST_FAILED", failure));
  }
}
