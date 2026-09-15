import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.infrastructure.search.OpenSearchDerivedIndexAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/** Use the compiled adapter; ensure only creates/checks the explicitly named isolated index. */
public final class DerivedSearchIndexProbe {
  public static void main(String[] args) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    var adapter = new OpenSearchDerivedIndexAdapter("http://127.0.0.1:19210",args[0],mapper);
    int dimensions = Integer.parseInt(args[1]);
    adapter.ensureIndex(dimensions);
    if (args.length == 2) {
      System.out.println("{\"status\":\"PASS_COMPILED_ADAPTER_MAPPING_ENSURE\"}");return;
    }
    var vector=mapper.readTree(new java.io.File(args[2])).path("vector");
    java.util.ArrayList<Float> values=new java.util.ArrayList<>();vector.forEach(v->values.add(v.floatValue()));
    var filters = new SearchFilters(null,null,null,List.of());
    var summary = adapter.searchSummaryVector(values,filters,20);
    var wiki = adapter.searchWikiVector(values,filters,20);
    System.out.println(mapper.writeValueAsString(Map.of("status","PASS_REAL_DERIVED_VECTOR_SEARCH",
        "summaryIds",summary.stream().map(c->c.id()).toList(),
        "wikiIds",wiki.stream().map(c->c.id()).toList(),"dimensions",values.size())));
  }
}
