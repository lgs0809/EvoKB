package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.*;
import cn.lgs.knowledgebase.application.port.Ports.ParsedBlock;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.infrastructure.parser.TextDocumentParserAdapter;
import cn.lgs.knowledgebase.infrastructure.raw.FrozenRawEncodingConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** Authored Gold is external; this helper calls immutable production parser/builder/tokenizer. */
public final class RawChunkMatrixProbe {
  private static final ObjectMapper JSON = new ObjectMapper();

  public static void main(String[] args) throws Exception {
    Path fixtures = Path.of(args[0]), tokenizerManifest = Path.of(args[1]), output = Path.of(args[2]);
    JsonNode manifest = JSON.readTree(fixtures.resolve("manifest.json").toFile());
    JsonNode tokenizer = JSON.readTree(tokenizerManifest.toFile());
    var configuration = new EffectiveConfiguration("http://127.0.0.1", "", "gpt-5.6-luna", "gpt-5.6-terra", 60,
        "http://127.0.0.1:8110", "", tokenizer.path("model").asText(), 1024, 8, 60,
        "http://127.0.0.1:8110", "", "Qwen3-VL-Reranker", 60, Instant.EPOCH);
    Store store = new Store() {
      public EffectiveConfiguration current() { return configuration; }
      public EffectiveConfiguration update(UpdateCommand command) { throw new UnsupportedOperationException("offline"); }
    };
    var results = new ArrayList<Map<String, Object>>();
    long suiteStart = System.nanoTime();
    Files.createDirectories(output.getParent().resolve(output.getFileName() + ".documents"));
    try (var frozen = new FrozenRawEncodingConfiguration(tokenizerManifest.toString(), JSON, store,
        "whole-input-no-truncation-v1:openai-embeddings-dimensions:server-qwen-document-template-v1")) {
      RawTokenCounter tokens = frozen.tokens();
      var parser = new TextDocumentParserAdapter(JSON);
      for (JsonNode caseGold : manifest.path("cases")) {
        String id = caseGold.path("id").asText(), filename = caseGold.path("filename").asText();
        long documentStart = System.nanoTime();
        byte[] original = Files.readAllBytes(fixtures.resolve(filename));
        String actualSha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));
        var parsed = parser.parse(filename, new java.io.ByteArrayInputStream(original));
        var blocks = RawKnowledgeBuilder.rawBlocks(id + "-version-1", parsed.blocks(), tokens);
        Set<String> knownIds = new HashSet<>(); blocks.forEach(block -> knownIds.add(block.id()));
        Map<String, Boolean> common = new LinkedHashMap<>();
        common.put("originalBytesMatchFrozenGold", actualSha.equals(caseGold.path("sha256").asText()) && original.length == caseGold.path("sizeBytes").asInt());
        common.put("nativeParsedOriginalPositionsMatchAuthoredGold", positionsMatch(parsed.blocks(), caseGold.path("expectedParsedBlocks")));
        common.put("sourceNativeCoordinatesNeverInventPdfPageOrBbox", parsed.blocks().stream().allMatch(b -> b.page() == null && b.bbox().isEmpty() && b.pageSize().isEmpty())
            && blocks.stream().allMatch(b -> b.pageStart() == null && b.pageEnd() == null && b.bbox().isEmpty()));
        var metadata = new Metadata("CHDEV isolated development", "Development fixture", List.of("CHDEV"), "", null, caseGold.path("family").asText());
        for (JsonNode profileGold : manifest.path("profiles")) {
          RawChunkProfile profile = new RawChunkProfile(profileGold.path("target").asInt(), profileGold.path("hardLimit").asInt(), profileGold.path("overlap").asInt(), profileGold.path("tail").asInt());
          long start = System.nanoTime();
          Map<String, Object> result = new LinkedHashMap<>();
          Map<String, Boolean> checks = new LinkedHashMap<>(common);
          result.put("caseId", id); result.put("profile", profile.identity());
          result.put("targetTokens", profile.targetTokens()); result.put("overlapTokens", profile.overlapTokens());
          try {
            var chunks = RawKnowledgeBuilder.smartChunks(id, id + "-version-1", metadata, blocks, profile, tokens);
            checks.put("actualRenderedNativeTokenCountWithin900", !chunks.isEmpty() && chunks.stream().allMatch(c -> tokens.count(c.content()) == c.tokenCount() && c.tokenCount() <= 900));
            checks.put("everyChunkResolvesOnlyItsActualSourceBlocks", chunks.stream().allMatch(c -> !c.sourceBlockIds().isEmpty() && knownIds.containsAll(c.sourceBlockIds()) && c.documentId().equals(id) && c.versionId().equals(id + "-version-1")));
            boolean clauses = true;
            List<Map<String, Object>> clauseReadback = new ArrayList<>();
            for (JsonNode sentence : caseGold.path("authoredSentences")) {
              String exact = sentence.asText();
              List<String> originalSources = blocks.stream().filter(b -> b.content().contains(exact)).map(b -> b.id()).toList();
              List<String> covering = chunks.stream().filter(c -> c.content().contains(exact) && c.sourceBlockIds().stream().anyMatch(originalSources::contains)).map(c -> c.id()).toList();
              clauses &= !originalSources.isEmpty() && !covering.isEmpty();
              clauseReadback.add(Map.of("authoredSentence", exact, "originalSourceBlockIds", originalSources, "coveringChunkIds", covering));
            }
            checks.put("everyAuthoredFullSentenceIncludingNegationAndConditionsCoveredTogether", clauses);
            var tableChunks = chunks.stream().filter(c -> "table".equals(c.structureType())).toList();
            Map<Integer, List<Element>> rows = new LinkedHashMap<>();
            boolean headers = !tableChunks.isEmpty();
            for (var chunk : tableChunks) {
              var dom = Jsoup.parse(chunk.content());
              for (JsonNode required : caseGold.path("table").path("headers")) headers &= dom.select("thead").text().contains(required.asText());
              if (!caseGold.path("table").path("caption").isNull()) headers &= chunk.content().contains(caseGold.path("table").path("caption").asText());
              for (Element row : dom.select("tbody tr")) {
                try { rows.computeIfAbsent(Integer.parseInt(row.attr("data-source-row")), ignored -> new ArrayList<>()).add(row); }
                catch (NumberFormatException missingIdentity) { headers = false; }
              }
            }
            boolean rowCoverage = true, originals = true, noOverlap = true;
            var tableReadback = new ArrayList<Map<String, Object>>();
            Set<Integer> expectedRows = new LinkedHashSet<>();
            for (JsonNode rowGold : caseGold.path("table").path("rows")) {
              int sourceRow = rowGold.path("sourceRow").asInt(); expectedRows.add(sourceRow);
              List<Element> fragments = rows.getOrDefault(sourceRow, List.of());
              List<String> expectedCells = new ArrayList<>(); rowGold.path("cells").forEach(c -> expectedCells.add(c.asText()));
              noOverlap &= fragments.size() == 1;
              boolean one = fragments.size() == 1;
              if (one) {
                List<Element> cells = fragments.get(0).select("td");
                one = cells.size() == expectedCells.size();
                for (int col = 0; col < Math.min(cells.size(), expectedCells.size()); col++) {
                  one &= cells.get(col).wholeText().equals(expectedCells.get(col));
                  originals &= cells.get(col).attr("data-origin").equals(sourceRow + ":" + col);
                }
              }
              rowCoverage &= one;
              tableReadback.add(Map.of("originalSourceRow", sourceRow, "expectedCells", expectedCells, "actualFragmentCount", fragments.size(), "cellsExact", one));
            }
            rowCoverage &= rows.keySet().equals(expectedRows);
            checks.put("allTableHeadersUnitsAndCaptionRepeated", headers);
            checks.put("everyOriginalTableCellAndRowCoveredExactly", rowCoverage && originals);
            checks.put("tableDataRowOverlapIsZeroAcrossProfiles", noOverlap);
            result.put("chunkCount", chunks.size()); result.put("encodingTokens", chunks.stream().mapToLong(c -> c.tokenCount()).sum());
            result.put("maxRenderedTokens", chunks.stream().mapToInt(c -> c.tokenCount()).max().orElse(0));
            result.put("tableChunkCount", tableChunks.size()); result.put("clauses", clauseReadback); result.put("tableRows", tableReadback);
            result.put("chunks", chunks.stream().map(c -> Map.of("id", c.id(), "content", c.content(), "tokenCount", c.tokenCount(), "sourceBlockIds", c.sourceBlockIds(), "headingPath", c.headingPath(), "contentHash", c.contentHash(), "structureType", c.structureType())).toList());
          } catch (RuntimeException error) {
            checks.put("productionChunkExecutionCompleted", false); result.put("errorClass", error.getClass().getName()); result.put("error", Objects.toString(error.getMessage(), ""));
          }
          result.put("checks", checks); result.put("status", checks.values().stream().allMatch(Boolean::booleanValue) ? "PASS" : "FAIL");
          result.put("offlineChunkElapsedMillis", (System.nanoTime() - start) / 1_000_000d);
          Path detail = output.getParent().resolve(output.getFileName() + ".documents").resolve(id + "-" + profile.targetTokens() + "-" + profile.overlapTokens() + ".json");
          JSON.writerWithDefaultPrettyPrinter().writeValue(detail.toFile(), result);
          var summary = new LinkedHashMap<>(result); summary.remove("chunks"); summary.remove("clauses"); summary.remove("tableRows"); summary.put("detailFile", detail.toString()); results.add(summary);
        }
        System.out.println(id + " 9 profiles complete, offline parse+chunk ms=" + (System.nanoTime() - documentStart) / 1_000_000);
      }
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("status", results.stream().allMatch(r -> "PASS".equals(r.get("status"))) && results.size() == 360 ? "PASS_OFFLINE_CHUNK_40_BY_9" : "FAIL_OFFLINE_CHUNK_40_BY_9");
      report.put("documentCaseCount", 40); report.put("configurationExecutions", results.size());
      report.put("passedExecutions", results.stream().filter(r -> "PASS".equals(r.get("status"))).count());
      report.put("tokenizerIdentity", tokens.identity()); report.put("encodingProfileFingerprint", frozen.currentProfile().fingerprint());
      report.put("offlineElapsedMillis", (System.nanoTime() - suiteStart) / 1_000_000d); report.put("results", results);
      report.put("boundary", "Actual immutable production native HTML/CSV parser, structure builder and frozen DJL tokenizer. No HTTP/model/embedding/retrieval/QA calls; Gold questions preserved but not answered. Not forty MinerU/PDF parsing successes or a model-quality winner.");
      JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
      if (!report.get("status").toString().startsWith("PASS")) System.exit(1);
    }
  }

  private static boolean positionsMatch(List<ParsedBlock> parsed, JsonNode expected) {
    if (parsed.size() != expected.size()) return false;
    for (int i = 0; i < parsed.size(); i++) {
      ParsedBlock actual = parsed.get(i); JsonNode gold = expected.get(i);
      if (!actual.type().equals(gold.path("type").asText())) return false;
      if (gold.has("csvRecords")) {
        JsonNode rows = JSON.valueToTree(actual.raw().get("source_csv_rows"));
        if (rows.size() != gold.path("csvRecords").size()) return false;
        for (int n = 0; n < rows.size(); n++) {
          var row = rows.get(n); var record = gold.path("csvRecords").get(n);
          if (row.path("record").asLong() != record.path("record").asLong()
              || row.path("character_offset").asLong() != record.path("characterOffset").asLong()
              || !row.path("cells").equals(record.path("cells"))) return false;
        }
      } else {
        if (!Objects.toString(actual.raw().get("source_selector"), "").equals("#" + gold.path("selectorId").asText())
            || ((Number) actual.raw().getOrDefault("source_line", -1)).intValue() != gold.path("line").asInt()
            || ((Number) actual.raw().getOrDefault("source_column", -1)).intValue() != gold.path("column").asInt()) return false;
        if (gold.has("exactText") && !actual.text().equals(gold.path("exactText").asText())) return false;
      }
    }
    return true;
  }
}
