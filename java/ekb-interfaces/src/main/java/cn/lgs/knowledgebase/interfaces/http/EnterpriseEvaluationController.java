package cn.lgs.knowledgebase.interfaces.http;

import cn.lgs.knowledgebase.application.evaluation.ArtifactEvaluationApplicationService;
import cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.ArtifactEvalReport;
import cn.lgs.knowledgebase.interfaces.evaluation.GoldenJsonlLoader;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * ADMIN-only deterministic evaluation of persisted compiled artifacts against Golden JSONL cases.
 */
@RestController
@RequestMapping("/api/enterprise/evaluation")
public final class EnterpriseEvaluationController {
  private static final long MAX_GOLDEN_FILE_BYTES = 32L * 1024 * 1024;
  private final GoldenJsonlLoader loader;
  private final ArtifactEvaluationApplicationService service;

  public EnterpriseEvaluationController(
      GoldenJsonlLoader loader, ArtifactEvaluationApplicationService service) {
    this.loader = loader;
    this.service = service;
  }

  @PostMapping(value = "/artifacts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ArtifactEvalReport evaluate(
      @RequestPart("cases") MultipartFile cases, HttpServletRequest request) {
    EnterpriseRequestContext.admin(request);
    if (cases == null || cases.isEmpty())
      throw new IllegalArgumentException("Golden JSONL cases file is required");
    if (cases.getSize() > MAX_GOLDEN_FILE_BYTES)
      throw new IllegalArgumentException("Golden JSONL cases file exceeds 32 MiB");
    try (InputStream input = cases.getInputStream()) {
      return service.evaluate(loader.load(input));
    } catch (IOException e) {
      throw new IllegalStateException("cannot read Golden JSONL upload", e);
    }
  }
}
