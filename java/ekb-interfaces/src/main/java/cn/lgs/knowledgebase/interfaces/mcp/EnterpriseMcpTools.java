package cn.lgs.knowledgebase.interfaces.mcp;

import cn.lgs.knowledgebase.application.port.Ports.SourcePackageService;
import cn.lgs.knowledgebase.application.promotion.PromotionApplicationService;
import cn.lgs.knowledgebase.application.retrieval.RetrievalApplicationService;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;

public final class EnterpriseMcpTools {
    private final RetrievalApplicationService retrieval;
    private final PromotionApplicationService promotion;
    private final SourcePackageService packages;

    public EnterpriseMcpTools(RetrievalApplicationService retrieval, PromotionApplicationService promotion,
                              SourcePackageService packages) {
        this.retrieval = retrieval;
        this.promotion = promotion;
        this.packages = packages;
    }

    @Tool(name = "search_knowledge", description = "Search the enterprise knowledge base across Raw, Summary, Wiki and Graph. Every returned item is resolved back to raw evidence.")
    public Map<String,Object> searchKnowledge(
            @ToolParam(description = "Natural-language query") String query,
            @ToolParam(description = "Optional source department") String sourceDepartment,
            @ToolParam(description = "Optional material category") String materialCategory,
            @ToolParam(description = "Optional company") String company,
            @ToolParam(description = "Optional document type") String documentType,
            @ToolParam(description = "Maximum final results, normally 1-20") Integer limit) {
        SearchFilters filters = new SearchFilters(blank(sourceDepartment), blank(materialCategory), blank(company), blank(documentType), List.of());
        var result = retrieval.search(query, filters, limit == null ? 10 : limit);
        promotion.recordDeliveredSearch(query, filters, result);
        var items = result.results().stream().map(r -> Map.of(
                "result_type", r.candidate().lane().name(),
                "knowledge", r.candidate().knowledge(),
                "score", r.candidate().score(),
                "evidence", r.evidence())).toList();
        return Map.of("search_id", result.searchId(), "results", items);
    }

    @Tool(name = "package_sources", description = "Package original source files for selected evidence IDs into a ZIP with a manifest and return a temporary download URL.")
    public Object packageSources(@ToolParam(description = "Evidence IDs returned by search_knowledge") List<String> evidenceIds) {
        return packages.packageSources(evidenceIds);
    }

    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
