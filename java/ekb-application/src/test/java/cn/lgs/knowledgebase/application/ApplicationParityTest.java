package cn.lgs.knowledgebase.application;

import cn.lgs.knowledgebase.application.ask.AskApplicationService;
import cn.lgs.knowledgebase.application.port.Ports;
import cn.lgs.knowledgebase.application.retrieval.RetrievalApplicationService;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationParityTest {
    @Test
    void retrievalCallsOneUnifiedRerankerAfterFourLanesAndDropsUnresolvedCandidates() {
        List<Ports.RetrievalLane> lanes = new ArrayList<>();
        for (RetrievalDomain.LaneType type : RetrievalDomain.LaneType.values()) {
            lanes.add(new FakeLane(type));
        }
        AtomicInteger rerankCalls = new AtomicInteger();
        Ports.RerankerPort reranker = (query, docs) -> {
            rerankCalls.incrementAndGet();
            return java.util.stream.IntStream.range(0, docs.size()).mapToObj(i -> 1.0 - i * 0.01).toList();
        };
        Ports.EvidenceResolver resolver = ids -> ids.contains("missing") ? List.of() : List.of(evidence(ids.get(0)));
        var service = new RetrievalApplicationService(lanes, reranker, resolver, RetrievalDomain.Quotas.defaults(), 20, 400);

        var response = service.search("question", new KnowledgeDomain.SearchFilters(null, null, null, null, List.of()), 10);

        assertEquals(1, rerankCalls.get());
        assertEquals(4, response.results().size());
        assertTrue(response.results().stream().allMatch(r -> !r.evidence().isEmpty()));
    }

    @Test
    void askRejectsNaturalLanguageAnswerWithoutValidEvidenceCitation() {
        var retrieval = retrievalWithSingleSource();
        Ports.KnowledgeModel invalidModel = new StaticModel("这是一个没有引用的答案");
        var service = new AskApplicationService(retrieval, invalidModel, 24000);
        var response = service.ask("问题", new KnowledgeDomain.SearchFilters(null, null, null, null, List.of()), 8);
        assertTrue(response.evidenceOnly());
        assertFalse(response.citations().isEmpty());
    }

    @Test
    void askDeliversAnswerWhenCitationIsValid() {
        var retrieval = retrievalWithSingleSource();
        var service = new AskApplicationService(retrieval, new StaticModel("依据材料可确认该事实。[1]"), 24000);
        var response = service.ask("问题", new KnowledgeDomain.SearchFilters(null, null, null, null, List.of()), 8);
        assertFalse(response.evidenceOnly());
        assertEquals(1, response.citations().size());
        assertTrue(response.answer().contains("[1]"));
    }

    private static RetrievalApplicationService retrievalWithSingleSource() {
        Ports.RetrievalLane raw = new FakeLane(RetrievalDomain.LaneType.RAW);
        return new RetrievalApplicationService(List.of(raw), (q, docs) -> List.of(1.0),
                ids -> List.of(evidence(ids.get(0))), RetrievalDomain.Quotas.defaults(), 20, 400);
    }

    private static KnowledgeDomain.Evidence evidence(String id) {
        return new KnowledgeDomain.Evidence(id, "doc", "v1", "block", "original/doc/v1.pdf",
                Map.of("page_start", 1), "raw verified content", "hash");
    }

    private record FakeLane(RetrievalDomain.LaneType type) implements Ports.RetrievalLane {
        @Override public List<RetrievalDomain.Candidate> search(String query, KnowledgeDomain.SearchFilters filters, int limit) {
            return List.of(new RetrievalDomain.Candidate(type.name().toLowerCase(), "doc", "v1", type,
                    "knowledge " + type, 0, List.of("ev-" + type.name().toLowerCase()), Map.of()));
        }
    }

    private record StaticModel(String answer) implements Ports.KnowledgeModel {
        @Override public String generate(Ports.ModelRole role, Ports.ModelTask task, String systemPrompt, String userPrompt) { return answer; }
        @Override public <T> T generateStructured(Ports.ModelRole role, Ports.ModelTask task, String systemPrompt, String userPrompt, Class<T> outputType) {
            throw new UnsupportedOperationException();
        }
    }
}
