package cn.lgs.knowledgebase.application.usage;

import cn.lgs.knowledgebase.application.port.Ports.StoredObject;
import java.io.IOException;
import java.io.OutputStream;

/** A receipt follows exact-length copying, stream closure and the HTTP response flush. */
public final class CompletePackageTransfer {
  private CompletePackageTransfer() {}

  @FunctionalInterface
  public interface FlushResponse {
    void flush() throws IOException;
  }

  public static void send(
      StoredObject stored,
      OutputStream output,
      FlushResponse flush,
      java.util.function.LongConsumer completed)
      throws IOException {
    long transferred;
    try (var input = stored.stream()) {
      transferred = input.transferTo(output);
      if (stored.size() <= 0 || transferred != stored.size())
        throw new IOException("package transfer length does not match stored object");
    }
    flush.flush();
    completed.accept(transferred);
  }
}
