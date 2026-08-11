package cn.lgs.knowledgebase.infrastructure.upload;

import cn.lgs.knowledgebase.application.upload.MultipartUploadApplicationService;
import org.springframework.scheduling.annotation.Scheduled;

/** Hourly housekeeping operates only on the durable upload rows, never a bucket-wide deletion. */
public final class MultipartUploadRecovery {
  private final MultipartUploadApplicationService uploads;

  public MultipartUploadRecovery(MultipartUploadApplicationService uploads) {
    this.uploads = uploads;
  }

  @Scheduled(fixedDelay = 3600000, initialDelay = 15000)
  public void reconcile() {
    uploads.recover();
  }
}
