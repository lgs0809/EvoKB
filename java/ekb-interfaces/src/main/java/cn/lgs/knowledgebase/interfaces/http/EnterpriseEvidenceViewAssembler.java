package cn.lgs.knowledgebase.interfaces.http;

import cn.lgs.knowledgebase.application.access.KnowledgeAccessService;
import cn.lgs.knowledgebase.application.operations.EnterpriseOperations;
import org.springframework.stereotype.Component;

/** Current-authority projection shared by search, direct ask and durable queries. */
@Component
public final class EnterpriseEvidenceViewAssembler {
  private final EnterpriseOperations.Repository operations;
  private final KnowledgeAccessService access;

  public EnterpriseEvidenceViewAssembler(
      EnterpriseOperations.Repository operations, KnowledgeAccessService access) {
    this.operations = operations;
    this.access = access;
  }

  public java.util.function.Function<
          cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence, HttpViews.EvidenceView>
      forUser(String userId) {
    int clearance = access.clearance(userId);
    var filenames = new java.util.HashMap<String, String>();
    return evidence ->
        HttpViews.evidence(
            evidence,
            filenames.computeIfAbsent(
                evidence.versionId(),
                ignored ->
                    operations
                        .evidence(evidence.id(), clearance)
                        .filter(
                            detail ->
                                detail.versionId().equals(evidence.versionId())
                                    && detail.documentId().equals(evidence.documentId()))
                        .map(EnterpriseOperations.EvidenceDetail::sourceFilename)
                        .orElseThrow(
                            cn.lgs.knowledgebase.application.access.KnowledgeAccessService.Denied
                                ::new)));
  }
}
