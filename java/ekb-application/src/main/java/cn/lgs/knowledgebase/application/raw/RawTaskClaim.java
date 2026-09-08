package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileItem;
import java.time.Instant;

public record RawTaskClaim(FileItem file, String ownerId, long epoch, Instant deadline) {
  public RawTaskClaim {
    if (file == null || ownerId == null || ownerId.isBlank() || epoch <= 0 || deadline == null)
      throw new IllegalArgumentException("A durable raw claim requires file/owner/epoch/deadline");
  }

  public String scope() {
    return file.fileId() + ":e" + epoch;
  }
}
