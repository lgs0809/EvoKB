package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.application.port.Ports.ParsedBlock;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.infrastructure.raw.LocalEmbeddingTokenizer;
import cn.lgs.knowledgebase.infrastructure.raw.FrozenRawEncodingConfiguration;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;

public final class RawTokenizerProbe {
  public static void main(String[] args) throws Exception {
    ObjectMapper json = new ObjectMapper();
    var manifest = json.readTree(Path.of(args[0]).resolve("manifest.json").toFile());
    var input = json.readTree(Path.of(args[1]).toFile());
    List<String> texts = new ArrayList<>(); input.path("input").forEach(item -> texts.add(item.asText()));
    try (var tokens = new LocalEmbeddingTokenizer(Path.of(args[0]).resolve("tokenizer.json"),
        manifest.path("tokenizerRevision").asText(), manifest.path("files").path("tokenizer.json").asText(),
        manifest.path("rendering").path("prefix").asText(), manifest.path("rendering").path("suffix").asText())) {
      Map<String,Object> checks = new LinkedHashMap<>();
      java.util.concurrent.atomic.AtomicReference<EffectiveConfiguration> configuration = new java.util.concurrent.atomic.AtomicReference<>(new EffectiveConfiguration(
          "http://127.0.0.1", "", "gpt-5.6-luna", "gpt-5.6-terra",60,"http://127.0.0.1:8110", "",manifest.path("model").asText(),1024,8,60,"http://127.0.0.1:8110","","Qwen3-VL-Reranker",60,Instant.EPOCH));
      Store store = new Store() {
        public EffectiveConfiguration current() { return configuration.get(); }
        public EffectiveConfiguration update(UpdateCommand command) { throw new UnsupportedOperationException(); }
      };
      try (var frozen = new FrozenRawEncodingConfiguration(Path.of(args[0]).resolve("manifest.json").toString(), json,store,"probe-no-inference:complete-document-input-v1")) {
        checks.put("allSixFrozenAssetsAndNativeRenderingVerified", frozen.tokens().identity().equals(tokens.identity())
            && texts.stream().allMatch(value -> frozen.tokens().count(value)==tokens.count(value))
            && frozen.profiles().get().modelRevision().equals(manifest.path("modelRevision").asText()));
        var before=frozen.profiles().get();
        var c=configuration.get();
        configuration.set(new EffectiveConfiguration(c.chatBaseUrl(),c.chatApiKey(),c.smallModel(),c.largeModel(),c.chatTimeoutSeconds(),"http://127.0.0.1:8111",c.embeddingApiKey(),c.embeddingModel(),c.embeddingDimensions(),c.embeddingBatchSize(),c.embeddingTimeoutSeconds(),c.rerankBaseUrl(),c.rerankApiKey(),c.rerankModel(),c.rerankTimeoutSeconds(),c.updatedAt()));
        checks.put("endpointChangesInvalidateProfile",!before.fingerprint().equals(frozen.profiles().get().fingerprint()));
        configuration.set(new EffectiveConfiguration(c.chatBaseUrl(),c.chatApiKey(),c.smallModel(),c.largeModel(),c.chatTimeoutSeconds(),c.embeddingBaseUrl(),c.embeddingApiKey(),"unknown-model",c.embeddingDimensions(),c.embeddingBatchSize(),c.embeddingTimeoutSeconds(),c.rerankBaseUrl(),c.rerankApiKey(),c.rerankModel(),c.rerankTimeoutSeconds(),c.updatedAt()));
        boolean rejected=false;try { frozen.profiles().get(); } catch(IllegalStateException mismatch) { rejected=mismatch.getMessage().startsWith("ENCODING_PROFILE_MISMATCH:"); }
        checks.put("unknownActiveModelRejectedWithoutFallback",rejected);
      }
      Metadata metadata = new Metadata("Synthetic diligence department", "Synthetic", List.of(), "", null, "CONTRACT");
      String condition = "未取得合法发票前付款义务不发生，取得合法发票并完成验收后60日内付款；金额900万元，质保24个月。";
      String text = condition.repeat(50);
      var blocks = RawKnowledgeBuilder.rawBlocks("native-token-version", List.of(new ParsedBlock("text", "第四条 付款条件", 1, 2, List.of(100d,200d,800d,400d), List.of(595d,842d), "", "", "", Map.of()),
          new ParsedBlock("text", text, 0, 2, List.of(100d,410d,800d,700d), List.of(595d,842d), "", "", "", Map.of())), tokens);
      var chunks = RawKnowledgeBuilder.smartChunks("native-token-doc", "native-token-version", metadata, blocks, RawChunkProfile.DEFAULT, tokens);
      checks.put("actualTokenBudgetIncludingTitlePrompt", chunks.size()>1 && chunks.stream().allMatch(chunk -> tokens.count(chunk.content()) == chunk.tokenCount() && chunk.tokenCount()<=900));
      checks.put("pageBboxAndOriginalProofRetained", blocks.get(1).pageStart()==3 && blocks.get(1).bbox().size()==4 && chunks.stream().allMatch(chunk -> chunk.sourceBlockIds().contains(blocks.get(1).id())));
      checks.put("allOriginalClausesCovered", chunks.stream().map(RawKnowledgeBuilder.ChunkDraft::content).reduce("", String::concat).split("未取得合法发票前付款义务不发生",-1).length-1>=50);
      StringBuilder table = new StringBuilder("<table><tr><th>合同编号</th><th>金额（万元）</th><th>付款条件</th></tr>");
      for(int i=0;i<40;i++) table.append("<tr><td>HT-").append(i).append("</td><td>").append(i+1).append("</td><td>").append(condition).append("</td></tr>");
      table.append("</table>");
      var tableBlocks = RawKnowledgeBuilder.rawBlocks("native-table-version", List.of(new ParsedBlock("table", "", 0, 0, List.of(), List.of(), "", table.toString(), table.toString(), Map.of("table_caption", List.of("回款计划"), "table_footnote",List.of("单位：万元，未取得发票不付款")))),tokens);
      var tableChunks = RawKnowledgeBuilder.smartChunks("native-table-doc","native-table-version",metadata,tableBlocks,RawChunkProfile.DEFAULT,tokens);
      checks.put("tableHeadersUnitAndFootnoteRepeated", tableChunks.size()>1 && tableChunks.stream().allMatch(chunk -> chunk.content().contains("金额（万元）") && chunk.content().contains("未取得发票不付款") && chunk.tokenCount()<=900));
      List<String> rowIds=tableChunks.stream().flatMap(chunk -> org.jsoup.Jsoup.parse(chunk.content()).select("tbody tr").stream()).map(row -> row.attr("data-source-row")).toList();
      checks.put("dataRowsNeverOverlap",rowIds.size()==40 && new java.util.HashSet<>(rowIds).size()==40);
      String merged="<table><tr><td rowspan='2'>合同编号</td><td colspan='2'>付款计划（单位：万元）</td></tr><tr><td>金额</td><td>条件</td></tr>";
      for(int i=0;i<25;i++) merged+="<tr><td>HT-"+i+"</td><td>900</td><td>"+condition.repeat(i==0?35:1)+"</td></tr>";
      merged+="</table>";
      var mergedBlocks=RawKnowledgeBuilder.rawBlocks("merged-native",List.of(new ParsedBlock("table","",0,null,List.of(),List.of(),"",merged,merged,Map.of())),tokens);
      var mergedChunks=RawKnowledgeBuilder.smartChunks("merged-doc","merged-native",metadata,mergedBlocks,RawChunkProfile.DEFAULT,tokens);
      checks.put("nativeOfficeMultiRowHeaderAndLongCellRetained",mergedChunks.size()>1&&mergedChunks.stream().allMatch(chunk->chunk.tokenCount()<=900&&chunk.content().contains("合同编号")&&chunk.content().contains("单位：万元"))
          &&mergedChunks.stream().flatMap(chunk->org.jsoup.Jsoup.parse(chunk.content()).select("tbody tr").stream()).allMatch(row->Integer.parseInt(row.attr("data-source-row"))>=2));
      String mergedContent=mergedChunks.stream().map(RawKnowledgeBuilder.ChunkDraft::content).reduce("",String::concat);
      checks.put("splitCellsKeepTheirColumnMeaningAndCompleteFacts",mergedChunks.stream().filter(chunk->chunk.content().contains("未取得合法发票前付款义务不发生")).allMatch(chunk->chunk.content().contains("条件"))
          &&mergedContent.split("未取得合法发票前付款义务不发生",-1).length-1>=59
          &&java.util.stream.IntStream.range(0,25).allMatch(i->mergedContent.contains("HT-"+i+"<")));
      Map<String,Object> report = new LinkedHashMap<>();report.put("tokenizerIdentity",tokens.identity());report.put("localTokenCounts",texts.stream().map(tokens::count).toList());report.put("checks",checks);report.put("textChunks",chunks.stream().map(chunk -> Map.of("text",chunk.content(),"tokenCount",chunk.tokenCount(),"sourceBlocks",chunk.sourceBlockIds())).toList());report.put("tableChunks",tableChunks.stream().map(chunk -> Map.of("text",chunk.content(),"tokenCount",chunk.tokenCount())).toList());
      report.put("mergedTableChunks",mergedChunks.stream().map(chunk->Map.of("text",chunk.content(),"tokenCount",chunk.tokenCount())).toList());
      json.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(),report);
    }
  }
}
