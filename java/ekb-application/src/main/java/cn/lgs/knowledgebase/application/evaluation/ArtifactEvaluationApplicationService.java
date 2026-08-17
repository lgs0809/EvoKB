package cn.lgs.knowledgebase.application.evaluation;

import static cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.buildReport;
import static cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.evaluateCase;
import static cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.validateGoldenCase;

import cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.ActualArtifacts;
import cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.ArtifactCaseResult;
import cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.ArtifactEvalReport;
import cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.ArtifactGoldenCase;
import java.util.ArrayList;
import java.util.List;

/** Loads persisted compiled artifacts and evaluates them against deterministic Golden cases. */
public final class ArtifactEvaluationApplicationService {
  public interface ArtifactRepository {
    ActualArtifacts load(List<String> versionIds);
  }

  private final ArtifactRepository repository;

  public ArtifactEvaluationApplicationService(ArtifactRepository repository) {
    if (repository == null) throw new IllegalArgumentException("artifact repository is required");
    this.repository = repository;
  }

  public ArtifactEvalReport evaluate(List<ArtifactGoldenCase> cases) {
    if (cases == null || cases.isEmpty())
      throw new IllegalArgumentException("no artifact Golden cases loaded");
    List<ArtifactCaseResult> results = new ArrayList<>(cases.size());
    for (ArtifactGoldenCase item : cases) {
      validateGoldenCase(item);
      results.add(evaluateCase(item, repository.load(item.versionIds())));
    }
    return buildReport(results);
  }
}
