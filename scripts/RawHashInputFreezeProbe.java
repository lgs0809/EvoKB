package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.infrastructure.parser.TextDocumentParserAdapter;
import cn.lgs.knowledgebase.infrastructure.raw.FrozenRawEncodingConfiguration;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Freezes actual production inputs/tokens before any efficiency model experiment. */
public final class RawHashInputFreezeProbe {
  public static void main(String[] args) throws Exception {
    ObjectMapper json = new ObjectMapper(); Path root = Path.of(args[0]);
    var manifest = json.readTree(root.resolve("manifest.json").toFile());
    var tokenizer = json.readTree(Path.of(args[1]).toFile());
    var effective = new EffectiveConfiguration("http://127.0.0.1", "", "gpt-5.6-luna", "gpt-5.6-terra", 60,
        "http://127.0.0.1:8110", "", tokenizer.path("model").asText(), 1024, 8, 60,
        "http://127.0.0.1:8110", "", "Qwen3-VL-Reranker", 60, Instant.EPOCH);
    Store store = new Store() {
      public EffectiveConfiguration current() { return effective; }
      public EffectiveConfiguration update(UpdateCommand command) { throw new UnsupportedOperationException(); }
    };
    var parser = new TextDocumentParserAdapter(json);
    List<Map<String, Object>> documents = new ArrayList<>(); Map<Integer, Integer> full = new TreeMap<>(), changed = new TreeMap<>();
    Map<Integer, Long> fullTokens = new TreeMap<>(), changedTokens = new TreeMap<>();
    Map<String, Boolean> checks = new LinkedHashMap<>(); checks.put("allFrozenOriginalsExact", true); checks.put("threeIndependentBodyUnitsPerOriginal", true); checks.put("authoredBusinessGoldRetainedExactly", true); checks.put("allInputsWithinNative900Budget", true); checks.put("bodyUnitRatesMatchActualChangedInputs", true);
    try (var frozen = new FrozenRawEncodingConfiguration(args[1], json, store, "whole-input-no-truncation-v1:openai-embeddings-dimensions:server-qwen-document-template-v1")) {
      var profile = frozen.currentProfile(); var tokens = frozen.tokens();
      for (var gold : manifest.path("efficiency").path("documents")) {
        String id = gold.path("id").asText(); var metadata = new Metadata("Hash efficiency development", "Development", List.of(), "", null, "CONTRACT");
        Map<String, Object> document = new LinkedHashMap<>(); document.put("documentId", id);
        var versions = new ArrayList<Map<String, Object>>(); Set<String> baselineHashes = null;
        List<JsonNode> originals = new ArrayList<>(); originals.add(gold.path("baseline"));
        gold.path("versions").forEach(v -> originals.add(v.path("original")));
        for (int version = 0; version < originals.size(); version++) {
          var original = originals.get(version); Path path = root.resolve(original.path("path").asText()); byte[] bytes = Files.readAllBytes(path);
          boolean exact = bytes.length == original.path("sizeBytes").asInt() && cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).equals(original.path("sha256").asText());
          checks.merge("allFrozenOriginalsExact", exact, (a, b) -> a && b);
          long start = System.nanoTime(); var parsed = parser.parse(path.getFileName().toString(), new java.io.ByteArrayInputStream(bytes));
          var blocks = RawKnowledgeBuilder.rawBlocks(id + "-v" + version, parsed.blocks(), tokens);
          var chunks = RawKnowledgeBuilder.smartChunks(id, id + "-v" + version, metadata, blocks, RawChunkProfile.DEFAULT, tokens);
          checks.merge("threeIndependentBodyUnitsPerOriginal", chunks.size() == 3, (a, b) -> a && b);
          checks.merge("allInputsWithinNative900Budget", chunks.stream().allMatch(c -> c.tokenCount() <= 900 && c.tokenCount() == tokens.count(c.content())), (a, b) -> a && b);
          List<Map<String, Object>> inputs = new ArrayList<>(); Set<String> hashes = new LinkedHashSet<>();
          int newCount = 0; long newTokens = 0;
          for (var c : chunks) {
            String hash = cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(c.content()); hashes.add(hash);
            boolean isChanged = baselineHashes != null && !baselineHashes.contains(hash);
            if (isChanged) { newCount++; newTokens += c.tokenCount(); }
            inputs.add(Map.of("chunkId", c.id(), "fullInput", c.content(), "fullInputSha256", hash,
                "tokenCount", c.tokenCount(), "bodyHash", c.contentHash(), "sourceBlockIds", c.sourceBlockIds(),
                "encodingCacheKey", profile.key(id, c.content()), "changedFromBaseline", isChanged));
          }
          Map<String, Object> one = new LinkedHashMap<>(); one.put("source", original); one.put("inputs", inputs);
          one.put("nativeParseAndChunkMillis", (System.nanoTime() - start) / 1_000_000d);
          if (version == 0) { baselineHashes = hashes; one.put("version", "baseline"); document.put("baseline", one); }
          else {
            var expected = gold.path("versions").get(version - 1); int rate = expected.path("bodyUnitChangeRatePercent").asInt();
            one.put("bodyUnitChangeRatePercent", rate); one.put("actualChangedEncoderInputs", newCount);
            boolean correct = chunks.size() == 3 && chunks.get(0).content().contains(expected.path("gold").path("paymentAmount").asText() + "万元人民币")
                && chunks.get(0).content().contains("未取得发票前付款义务不发生")
                && chunks.get(0).content().contains("合法发票且完成书面验收")
                && chunks.get(1).content().contains(expected.path("gold").path("warrantyMonths").asText() + "个月")
                && chunks.get(2).content().contains(expected.path("gold").path("tableAmount").asText())
                && chunks.get(2).content().contains("合法发票且书面验收，缺一项不得付款");
            checks.merge("authoredBusinessGoldRetainedExactly", correct, (a, b) -> a && b);
            checks.merge("bodyUnitRatesMatchActualChangedInputs", newCount == expected.path("changedBodyUnits").size(), (a, b) -> a && b);
            full.merge(rate, chunks.size(), Integer::sum); changed.merge(rate, newCount, Integer::sum);
            fullTokens.merge(rate, chunks.stream().mapToLong(c -> c.tokenCount()).sum(), Long::sum); changedTokens.merge(rate, newTokens, Long::sum); versions.add(one);
          }
        }
        document.put("versions", versions); documents.add(document);
      }
      Map<String, Object> report = new LinkedHashMap<>(); report.put("checks", checks); report.put("documents", documents);
      report.put("documentCount", documents.size()); report.put("nativeOriginalParseExecutions", documents.size() * 6);
      report.put("fullRebuildInputCountsByRate", full); report.put("changedInputCountsByRate", changed);
      report.put("fullRebuildInputTokensByRate", fullTokens); report.put("changedInputTokensByRate", changedTokens);
      report.put("tokenizerIdentity", tokens.identity()); report.put("encodingProfile", profile); report.put("chunkProfileIdentity", RawChunkProfile.DEFAULT.identity());
      report.put("status", documents.size() == 100 && checks.values().stream().allMatch(Boolean.TRUE::equals) ? "PASS_FROZEN_ACTUAL_HASH_INPUTS_NO_INFERENCE" : "FAIL_FROZEN_ACTUAL_HASH_INPUTS_NO_INFERENCE");
      report.put("boundary", "Offline 100 development documents, 600 frozen originals, immutable production parser/chunk/native DJL. Counts are actual planned input sets, not actual model calls or end-to-end searchable timing. No parsed cache or model-revision migration claimed.");
      json.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(), report);
      if (!report.get("status").toString().startsWith("PASS")) System.exit(1);
    }
  }
}
