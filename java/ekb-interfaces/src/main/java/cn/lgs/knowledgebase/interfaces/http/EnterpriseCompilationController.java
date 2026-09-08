package cn.lgs.knowledgebase.interfaces.http;

import cn.lgs.knowledgebase.application.compilation.CompilationCommands;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** ADMIN commands are durable jobs; HTTP requests never run models or bypass compilation locks. */
@RestController
@RequestMapping("/api/enterprise/compilation")
public final class EnterpriseCompilationController {
  private final CompilationCommands.Store commands;

  public EnterpriseCompilationController(CompilationCommands.Store commands) {
    this.commands = commands;
  }

  @PostMapping("/summary")
  public ResponseEntity<GenerationTask> summary(
      @RequestBody SummaryRequest body, HttpServletRequest request) {
    return submit(
        request,
        new CompilationCommands.Request(
            CompilationCommands.Kind.SUMMARY, body.versionId(), List.of(), "", "", "", "", 0));
  }

  @PostMapping("/graph")
  public ResponseEntity<GenerationTask> graph(
      @RequestBody GraphRequest body, HttpServletRequest request) {
    return submit(
        request,
        new CompilationCommands.Request(
            CompilationCommands.Kind.GRAPH, "", List.of(body.summaryId()), "", "", "", "", 0));
  }

  @PostMapping("/concept-projection")
  public ResponseEntity<GenerationTask> conceptProjection(
      @RequestBody GraphRequest body, HttpServletRequest request) {
    return submit(
        request,
        new CompilationCommands.Request(
            CompilationCommands.Kind.CONCEPT_PROJECTION,
            "",
            List.of(body.summaryId()),
            "",
            "",
            "",
            "",
            0));
  }

  @PostMapping("/wiki")
  public ResponseEntity<GenerationTask> wiki(
      @RequestBody WikiRequest body, HttpServletRequest request) {
    return submit(
        request,
        new CompilationCommands.Request(
            CompilationCommands.Kind.WIKI,
            "",
            body.summaryIds(),
            body.wikiType(),
            body.title(),
            body.canonicalSubjectId(),
            "",
            0));
  }

  @PostMapping("/wiki-keywords")
  public ResponseEntity<GenerationTask> wikiKeywords(
      @RequestBody WikiKeywordRequest body, HttpServletRequest request) {
    return submit(
        request,
        new CompilationCommands.Request(
            CompilationCommands.Kind.WIKI_KEYWORDS,
            "",
            List.of(),
            "",
            "",
            "",
            body.wikiId(),
            body.wikiVersion()));
  }

  private ResponseEntity<GenerationTask> submit(
      HttpServletRequest request, CompilationCommands.Request command) {
    var user = EnterpriseRequestContext.admin(request);
    return ResponseEntity.accepted().body(commands.submit(user.id(), command));
  }

  public record SummaryRequest(String versionId) {}

  public record GraphRequest(String summaryId) {}

  public record WikiRequest(
      String wikiType, String title, String canonicalSubjectId, List<String> summaryIds) {}

  public record WikiKeywordRequest(String wikiId, long wikiVersion) {}
}
