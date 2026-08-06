package cn.lgs.knowledgebase.application.operations;

import cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.User;

/** The same explicit classification rule for initial uploads and later document metadata. */
public final class DocumentClassificationPolicy {
  private DocumentClassificationPolicy() {}

  public static void requireAllowed(User actor, Integer securityLevel) {
    requireAllowed(actor != null && actor.administrator(), securityLevel);
  }

  /** Uploading Raw requires the same clearance later checked by reservation and completion. */
  public static void requireUploadAllowed(User actor, Integer securityLevel) {
    requireAllowed(actor, securityLevel);
    if (securityLevel != null && securityLevel > actor.securityClearance())
      throw new ManagedIngestionApplicationService.Forbidden(
          "source classification exceeds uploader clearance");
  }

  public static void requireAllowed(boolean administrator, Integer securityLevel) {
    if (securityLevel == null) return;
    if (!administrator)
      throw new ManagedIngestionApplicationService.Forbidden(
          "only admins can change document classification");
    requireValid(securityLevel);
  }

  public static void requireValid(Integer securityLevel) {
    if (securityLevel != null && (securityLevel < 10 || securityLevel > 127))
      throw new IllegalArgumentException("security level must be between 10 and 127");
  }
}
