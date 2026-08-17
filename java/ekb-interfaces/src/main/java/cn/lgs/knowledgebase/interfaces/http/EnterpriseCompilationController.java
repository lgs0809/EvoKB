package cn.lgs.knowledgebase.interfaces.http;

import cn.lgs.knowledgebase.application.compilation.GraphCompilationService;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService;
import cn.lgs.knowledgebase.application.compilation.WikiCompilationService;
import cn.lgs.knowledgebase.application.compilation.WikiKeywordProjectionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ADMIN-only runtime entry points equivalent to the Go summary/graph/wiki commands. */
@RestController
@RequestMapping("/api/enterprise/compilation")
public final class EnterpriseCompilationController {
    private final SummaryCompilationService summaries;
    private final GraphCompilationService graph;
    private final WikiCompilationService wikis;
    private final WikiKeywordProjectionService wikiKeywords;

    public EnterpriseCompilationController(SummaryCompilationService summaries, GraphCompilationService graph,
                                           WikiCompilationService wikis, WikiKeywordProjectionService wikiKeywords) {
        this.summaries = summaries;
        this.graph = graph;
        this.wikis = wikis;
        this.wikiKeywords = wikiKeywords;
    }

    @PostMapping("/summary")
    public List<SummaryCompilationService.PreparedSummary> summary(@RequestBody SummaryRequest body, HttpServletRequest request) {
        EnterpriseRequestContext.admin(request);
        return summaries.compileVersion(body.versionId());
    }

    @PostMapping("/graph")
    public GraphCompilationService.CompileResult graph(@RequestBody GraphRequest body, HttpServletRequest request) {
        EnterpriseRequestContext.admin(request);
        return graph.compileSummary(body.summaryId());
    }

    @PostMapping("/wiki")
    public WikiCompilationService.CompileResult wiki(@RequestBody WikiRequest body, HttpServletRequest request) {
        EnterpriseRequestContext.admin(request);
        return wikis.compile(new WikiCompilationService.CompileRequest(body.wikiType(), body.title(), body.canonicalSubjectId(), body.summaryIds()));
    }

    @PostMapping("/wiki-keywords")
    public WikiKeywordProjectionService.ProjectionResult wikiKeywords(@RequestBody WikiKeywordRequest body, HttpServletRequest request) {
        EnterpriseRequestContext.admin(request);
        return wikiKeywords.project(body.wikiId(), body.wikiVersion());
    }

    public record SummaryRequest(String versionId) {}
    public record GraphRequest(String summaryId) {}
    public record WikiRequest(String wikiType, String title, String canonicalSubjectId, List<String> summaryIds) {}
    public record WikiKeywordRequest(String wikiId, long wikiVersion) {}
}
