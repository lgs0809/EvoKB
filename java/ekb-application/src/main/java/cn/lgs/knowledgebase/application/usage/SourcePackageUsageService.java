package cn.lgs.knowledgebase.application.usage;

import cn.lgs.knowledgebase.application.access.KnowledgeAccessService;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.query.QueryDomain.SessionStatus;
import java.util.List;

/**
 * Answer attribution comes from owned, persisted answers, never from client-supplied source IDs.
 */
public final class SourcePackageUsageService {
  private final QuerySessionRepository sessions;
  private final KnowledgeAccessService access;
  private final DownloadUsageRepository repository;

  public SourcePackageUsageService(
      QuerySessionRepository sessions,
      KnowledgeAccessService access,
      DownloadUsageRepository repository) {
    this.sessions = java.util.Objects.requireNonNull(sessions);
    this.access = java.util.Objects.requireNonNull(access);
    this.repository = java.util.Objects.requireNonNull(repository);
  }

  public void validate(String userId, List<PackageAnswer> answers) {
    for (var answer : answers) {
      var session =
          sessions.findSession(answer.sessionId()).orElseThrow(KnowledgeAccessService.Denied::new);
      if (!session.userId().equals(userId) || !access.permitsSession(session))
        throw new KnowledgeAccessService.Denied();
      if (session.status() != SessionStatus.ANSWERED
          && session.status() != SessionStatus.INSUFFICIENT)
        throw new IllegalArgumentException("only completed answers can contribute source usage");
      if (answer.evidenceIds().isEmpty()
          || !session.citationEvidenceIds().containsAll(answer.evidenceIds()))
        throw new IllegalArgumentException(
            "package sources must be citations of the attributed answer");
    }
  }

  public void register(String userId, String packageId, PackageManifest manifest, long size) {
    validate(userId, manifest.answers());
    repository.saveManifest(userId, packageId, manifest, size);
  }

  public void completed(String userId, String packageId, long transferredBytes) {
    repository.recordCompletedTransfer(userId, packageId, transferredBytes);
  }
}
