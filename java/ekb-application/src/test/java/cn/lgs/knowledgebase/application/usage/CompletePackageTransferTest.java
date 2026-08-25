package cn.lgs.knowledgebase.application.usage;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.port.Ports.StoredObject;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CompletePackageTransferTest {
  @Test
  void receiptFollowsCloseAndActualResponseFlush() throws Exception {
    var stages = new StringBuilder();
    var input =
        new ByteArrayInputStream(new byte[] {1, 2, 3}) {
          @Override
          public void close() {
            stages.append("closed;");
          }
        };
    var out = new ByteArrayOutputStream();
    CompletePackageTransfer.send(
        new StoredObject(input, 3, "application/zip"),
        out,
        () -> stages.append("flushed;"),
        bytes -> {
          assertEquals(3, bytes);
          stages.append("recorded;");
        });
    assertEquals("closed;flushed;recorded;", stages.toString());
    assertArrayEquals(new byte[] {1, 2, 3}, out.toByteArray());
  }

  @Test
  void shortStoredStreamCannotProduceReceipt() {
    rejected(new ByteArrayInputStream(new byte[] {1}), 2, new ByteArrayOutputStream(), () -> {});
  }

  @Test
  void oversizedStoredStreamCannotProduceReceipt() {
    rejected(new ByteArrayInputStream(new byte[] {1, 2}), 1, new ByteArrayOutputStream(), () -> {});
  }

  @Test
  void failedOutputCannotProduceReceipt() {
    rejected(
        new ByteArrayInputStream(new byte[] {1, 2}),
        2,
        new OutputStream() {
          public void write(int value) throws IOException {
            throw new IOException("client disconnected");
          }
        },
        () -> {});
  }

  @Test
  void failedResponseFlushCannotProduceReceipt() {
    rejected(
        new ByteArrayInputStream(new byte[] {1, 2}),
        2,
        new ByteArrayOutputStream(),
        () -> {
          throw new IOException("connection lost on flush");
        });
  }

  @Test
  void failedInputCloseCannotProduceReceipt() {
    rejected(
        new ByteArrayInputStream(new byte[] {1, 2}) {
          @Override
          public void close() throws IOException {
            throw new IOException("source failure");
          }
        },
        2,
        new ByteArrayOutputStream(),
        () -> {});
  }

  private void rejected(
      InputStream input,
      long size,
      OutputStream output,
      CompletePackageTransfer.FlushResponse flush) {
    var receipts = new AtomicInteger();
    assertThrows(
        IOException.class,
        () ->
            CompletePackageTransfer.send(
                new StoredObject(input, size, "application/zip"),
                output,
                flush,
                bytes -> receipts.incrementAndGet()));
    assertEquals(0, receipts.get());
  }
}
