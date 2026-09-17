package cn.lgs.knowledgebase.application.compilation;

import static cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.deterministicUuid;
import static cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.CompilationRepository;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.DocumentUnit;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryFact;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryRecord;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.VersionSource;
import cn.lgs.knowledgebase.application.port.Ports.DerivedContext;
import cn.lgs.knowledgebase.application.port.Ports.DerivedSearchIndex;
import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import cn.lgs.knowledgebase.application.port.Ports.KnowledgeModel;
import cn.lgs.knowledgebase.application.port.Ports.ModelRole;
import cn.lgs.knowledgebase.application.port.Ports.ModelTask;
import cn.lgs.knowledgebase.application.port.Ports.ObjectStore;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.ArtifactStatus;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Claim;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.SummaryArtifact;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SummaryCompilationService {
  // Keep multi-candidate grounding requests small enough for the configured remote judge to
  // return the complete decision array reliably.  Every candidate is still judged exactly once
  // and the aggregate remains fail-closed: unknown, duplicate, missing, or null decisions abort
  // compilation rather than being accepted implicitly.
  private static final int GROUNDING_JUDGE_BATCH_SIZE = 4;
  // Keep each summary extraction request bounded for large real-world reports while
  // preserving every RawBlock in full. Batches are split only at block boundaries;
  // coverage/repair remains responsible for finding facts that span adjacent batches.
  // These are prompt-only boundaries.  The splitter preserves every source character and
  // never truncates, summarizes, or inserts an ellipsis; a conservative boundary keeps large
  // real reports below the observed remote-gateway latency while preserving every source
  // character across adjacent batches. A provider timeout/empty response still triggers the
  // exact recursive block-preserving splitter below.
  private static final int FACT_EXTRACTION_BATCH_CHARACTERS = 16_000;
  // Coverage responses are intentionally smaller than extraction prompts: the PASS FACTS list
  // is repeated for every batch, so keeping the raw-block slice bounded leaves response headroom
  // for a strict {missing:[...]} decision without dropping any source text.  As with extraction,
  // an explicit provider timeout splits the exact block stream recursively; the smaller slice
  // leaves response headroom for a strict missing[] decision without dropping source text.
  private static final int COVERAGE_JUDGE_BATCH_CHARACTERS = 16_000;
  // A real MinerU table may be persisted as one very large RawBlock (hundreds of thousands of
  // characters). Keep model requests bounded by splitting only at complete row/newline
  // boundaries. The concatenation of all prompt fragments is exactly the original block; this
  // never changes RawBlock/Evidence or the embedding input.
  // Keep each remote prompt below the gateway's observed long-request timeout. This is only a
  // transport boundary: splitModelText partitions the exact source string, never truncates it or
  // inserts an ellipsis, and RawBlock/Evidence/embedding content remain unchanged.  If the
  // provider still times out, the caller halves this prompt-only fragment without changing the
  // persisted source.
  private static final int MODEL_PROMPT_FRAGMENT_CHARACTERS = 16_000;
  private static final int GROUNDING_EVIDENCE_FRAGMENT_CHARACTERS = 4_000;
  private static final int MAX_COVERAGE_REPAIR_ROUNDS = 3;
  // Bound provider-empty response recovery. Each split preserves complete RawBlock boundaries,
  // but an always-empty provider must still fail closed instead of recursing indefinitely.
  private static final int MAX_PROVIDER_SPLIT_DEPTH = 2;

  public static final String GENERAL = "General / Mixed";
  private static final Set<String> STRUCTURES = Set.of("single", "bundle", "mixed");
  private static final int FALLBACK_UNIT_TARGET_CHARACTERS = 1200;
  private static final int LARGE_DOCUMENT_CLASSIFICATION_MAX_CHARACTERS = 100_000;
  private static final int LARGE_DOCUMENT_FALLBACK_UNIT_TARGET_CHARACTERS = 30_000;
  private static final int FALLBACK_HEADING_BREAK_MIN_CHARACTERS = 400;

  private final CompilationRepository repository;
  private final KnowledgeModel model;
  private final ObjectStore objects;
  private final EmbeddingPort embedding;
  private final DerivedSearchIndex derived;
  private final Clock clock;

  public SummaryCompilationService(
      CompilationRepository repository,
      KnowledgeModel model,
      ObjectStore objects,
      EmbeddingPort embedding,
      DerivedSearchIndex derived) {
    this(repository, model, objects, embedding, derived, Clock.systemUTC());
  }

  SummaryCompilationService(
      CompilationRepository repository,
      KnowledgeModel model,
      ObjectStore objects,
      EmbeddingPort embedding,
      DerivedSearchIndex derived,
      Clock clock) {
    this.repository = require(repository, "compilation repository");
    this.model = require(model, "knowledge model");
    this.objects = require(objects, "object store");
    this.embedding = require(embedding, "embedding");
    this.derived = require(derived, "derived search index");
    this.clock = require(clock, "clock");
    if (model.identity(ModelRole.LARGE).equalsIgnoreCase(model.identity(ModelRole.SMALL))) {
      throw new IllegalArgumentException(
          "summary generator and independent judge must be different model identities");
    }
  }

  public List<PreparedSummary> compileVersion(String versionId) {
    VersionSource source = repository.loadVersionSource(versionId);
    if (source.blocks().isEmpty() || source.evidenceByBlock().isEmpty()) {
      throw new IllegalStateException("active version has incomplete raw evidence: " + versionId);
    }
    List<DocumentUnit> units;
    // A full classification prompt is useful for ordinary documents, but sending hundreds of
    // thousands of characters to a remote chat endpoint is itself an avoidable transport risk.
    // For oversized real reports, plan deterministic units from the actual heading/block stream;
    // no source content is discarded and Summary extraction still consumes every block in full.
    if (renderBlocks(source.blocks()).length() > LARGE_DOCUMENT_CLASSIFICATION_MAX_CHARACTERS) {
      units = fallbackDocumentUnits(source, LARGE_DOCUMENT_FALLBACK_UNIT_TARGET_CHARACTERS);
    } else {
      try {
        Classification classification =
            model.generateStructured(
                ModelRole.LARGE,
                ModelTask.CLASSIFY_UNIT,
                classificationSystemPrompt(),
                classificationUserPrompt(source),
                Classification.class);
        units = normalizeClassification(source, classification);
      } catch (IllegalStateException failure) {
        if (!transientClassificationFailure(failure)
            && !missingResponseContent(failure, ModelTask.CLASSIFY_UNIT)
            && !gatewayTimeoutFailure(failure, ModelTask.CLASSIFY_UNIT)) throw failure;
        units = fallbackDocumentUnits(source);
      }
    }
    repository.replaceDocumentUnits(versionId, units);
    // The database keeps (version_id, unit_no) unique for idempotency. If a later model
    // classification changes a unit's descriptive fields, the upsert intentionally retains the
    // existing primary key because summaries may already reference it. Re-read persisted units
    // so the SummaryRecord always points at the actual FK parent rather than a newly proposed ID.
    List<DocumentUnit> persistedUnits = repository.loadDocumentUnits(versionId);
    if (!persistedUnits.isEmpty()) units = reconcilePersistedUnits(units, persistedUnits);

    // Summary compilation is persisted one unit at a time.  A provider or worker failure after
    // several units must therefore resume from the durable ACTIVE/PASS/PASS checkpoint instead
    // of sending those same units to the model again.  Only reuse a checkpoint produced by the
    // currently configured large model; a model-profile change intentionally rebuilds the unit.
    String currentLargeModel = model.identity(ModelRole.LARGE);
    Map<String, SummaryRecord> resumable = new LinkedHashMap<>();
    for (SummaryRecord summary : repository.loadActiveSummariesForVersion(versionId)) {
      if (summary.activeAndGrounded()
          && currentLargeModel.equals(summary.model())
          && resumable.putIfAbsent(summary.unitId(), summary) != null) {
        throw new IllegalStateException(
            "duplicate active summary checkpoint for unit " + summary.unitId());
      }
    }
    List<PreparedSummary> results = new ArrayList<>();
    for (DocumentUnit unit : units) {
      SummaryRecord checkpoint = resumable.get(unit.id());
      if (checkpoint != null) {
        results.add(new PreparedSummary(unit, checkpoint, 0));
      } else {
        results.add(compileUnit(source, unit));
      }
    }
    return List.copyOf(results);
  }

  private static List<DocumentUnit> reconcilePersistedUnits(
      List<DocumentUnit> proposed, List<DocumentUnit> persisted) {
    Map<Integer, DocumentUnit> byNumber = new LinkedHashMap<>();
    for (DocumentUnit unit : persisted) {
      if (byNumber.putIfAbsent(unit.unitNo(), unit) != null)
        throw new IllegalStateException(
            "duplicate persisted document unit number " + unit.unitNo());
    }
    List<DocumentUnit> resolved = new ArrayList<>(proposed.size());
    for (DocumentUnit unit : proposed) {
      DocumentUnit actual = byNumber.get(unit.unitNo());
      if (actual == null)
        throw new IllegalStateException(
            "persisted document unit missing number " + unit.unitNo() + " for " + unit.versionId());
      if (!unit.versionId().equals(actual.versionId()))
        throw new IllegalStateException("persisted document unit belongs to another version");
      resolved.add(actual);
    }
    return List.copyOf(resolved);
  }

  private PreparedSummary compileUnit(VersionSource source, DocumentUnit unit) {
    List<RawBlock> blocks =
        source.blocks().stream()
            .filter(
                block ->
                    block.blockNo() >= unit.startBlockNo() && block.blockNo() <= unit.endBlockNo())
            .toList();
    if (blocks.isEmpty())
      throw new IllegalStateException("summary unit has no raw blocks: " + unit.id());

    FactProposalSet first = extractFacts(unit, blocks, List.of(), List.of());
    List<SummaryFact> facts = groundFacts(unit, blocks, source.evidenceByBlock(), first.facts());
    CoverageDecision coverage =
        filterAlreadyCoveredMissing(checkCoverage(unit, blocks, passing(facts)), facts);
    int repairs = 0;
    int repairRounds = 0;
    Set<String> attemptedCoverage = new LinkedHashSet<>();
    while (coverage.missing() != null
        && !coverage.missing().isEmpty()
        && repairRounds < MAX_COVERAGE_REPAIR_ROUNDS) {
      repairRounds++;
      List<MissingFact> normalizedMissing = normalizeMissing(blocks, coverage.missing());
      if (normalizedMissing.isEmpty()) break;
      // A provider can repeat the exact same missing set after repair. Stop there and preserve
      // that explicit fail-closed decision rather than spinning through retries forever.
      String fingerprint = coverageFingerprint(normalizedMissing);
      if (!attemptedCoverage.add(fingerprint)) break;
      List<String> focus = normalizedMissing.stream().map(MissingFact::section).distinct().toList();
      Set<String> repairBlockIds =
          normalizedMissing.stream()
              .flatMap(missing -> missing.sourceBlockIds().stream())
              .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
      List<RawBlock> repairBlocks =
          blocks.stream().filter(block -> repairBlockIds.contains(block.id())).toList();
      FactProposalSet repair = extractFacts(unit, repairBlocks, focus, normalizedMissing);
      List<SummaryFact> repairFacts =
          groundFacts(unit, blocks, source.evidenceByBlock(), repair.facts());
      facts = mergeFacts(facts, repairFacts);
      // A Coverage Judge may identify a real Evidence block that the content-driven extractor
      // did not promote to a fact (for example, a short enumerated clause split out by MinerU).
      // Keep the evidence-first boundary intact by projecting the complete raw block verbatim
      // as a derived fact. This is not an LLM-generated claim: the source block itself is the
      // fact and its persisted Evidence is the sole support. Structural-only blocks are still
      // excluded, and the subsequent Coverage Judge remains authoritative/fail-closed.
      List<SummaryFact> directFacts =
          directEvidenceFactsForMissing(
              unit, blocks, source.evidenceByBlock(), normalizedMissing, facts);
      facts = mergeFacts(facts, directFacts);
      repairs += repairFacts.size() + directFacts.size();
      // Once a repair round has been requested, preserve the next Coverage Judge decision
      // verbatim. A repeated missing candidate is an explicit fail-closed signal even when it
      // points at a block that now has another PASS fact.
      coverage = checkCoverage(unit, blocks, passing(facts));
      coverage = new CoverageDecision(normalizeMissing(blocks, coverage.missing()));
    }

    boolean groundingPass =
        !facts.isEmpty() && facts.stream().allMatch(f -> "PASS".equals(f.groundingStatus()));
    boolean coveragePass = coverage.missing() == null || coverage.missing().isEmpty();
    String markdown = render(source.logicalName(), unit, facts);
    String summaryId =
        deterministicUuid(
            "summary:"
                + unit.id()
                + ":"
                + model.identity(ModelRole.LARGE)
                + ":"
                + sha256(markdown));
    List<SummaryFact> withSummary = new ArrayList<>(facts.size());
    for (SummaryFact fact : facts) {
      String storedFactId =
          deterministicUuid(
              "summary_fact:"
                  + summaryId
                  + ":"
                  + fact.section()
                  + ":"
                  + normalize(fact.factText()));
      withSummary.add(
          new SummaryFact(
              storedFactId,
              summaryId,
              fact.section(),
              fact.factText(),
              fact.evidenceBlockIds(),
              fact.evidenceIds(),
              fact.groundingStatus(),
              fact.groundingReason()));
    }
    String objectKey =
        "summary/"
            + source.documentId()
            + "/"
            + source.versionId()
            + "/"
            + unit.id()
            + "/"
            + summaryId
            + ".md";
    byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
    objects.put(
        objectKey, new ByteArrayInputStream(bytes), bytes.length, "text/markdown; charset=utf-8");
    String lifecycle = groundingPass && coveragePass ? "ACTIVE" : "INACTIVE";
    SummaryRecord stored =
        new SummaryRecord(
            summaryId,
            unit.id(),
            source.documentId(),
            source.versionId(),
            objectKey,
            sha256(markdown),
            groundingPass ? "PASS" : "FAIL",
            coveragePass ? "PASS" : "FAIL",
            lifecycle,
            model.identity(ModelRole.LARGE),
            markdown,
            withSummary,
            clock.instant());
    repository.saveSummary(stored);
    if (stored.activeAndGrounded()) {
      List<Claim> claims =
          withSummary.stream().map(f -> new Claim(f.factText(), f.evidenceIds(), 1.0)).toList();
      SummaryArtifact artifact =
          new SummaryArtifact(
              summaryId,
              source.documentId(),
              source.versionId(),
              markdown,
              claims,
              ArtifactStatus.ACTIVE,
              clock.instant());
      List<List<Float>> vectors = embedding.embed(List.of(markdown));
      if (vectors.size() != 1)
        throw new IllegalStateException("summary embedding response mismatch");
      derived.indexSummary(
          artifact,
          new DerivedContext(
              List.of(source.documentId()),
              List.of(source.versionId()),
              values(source.metadata().sourceDepartment()),
              values(source.metadata().materialCategory()),
              values(source.metadata().company()),
              values(source.metadata().documentType()),
              source.metadata().manualTags()),
          vectors.get(0));
    }
    return new PreparedSummary(unit, stored, repairs);
  }

  private static CoverageDecision filterAlreadyCoveredMissing(
      CoverageDecision decision, List<SummaryFact> facts) {
    if (decision == null || decision.missing() == null || decision.missing().isEmpty()) {
      return new CoverageDecision(List.of());
    }
    List<MissingFact> remaining =
        decision.missing().stream()
            .filter(
                missing ->
                    missing.sourceBlockIds() == null
                        || missing.sourceBlockIds().stream()
                            .map(SummaryCompilationService::trim)
                            .filter(id -> !id.isEmpty())
                            .anyMatch(id -> !alreadyCoveredByFact(missing, id, facts)))
            .toList();
    return new CoverageDecision(remaining);
  }

  private static boolean alreadyCoveredByFact(
      MissingFact missing, String sourceBlockId, List<SummaryFact> facts) {
    String candidate = normalize(missing.missingFactCandidate());
    if (candidate.isEmpty()) return false;
    return facts.stream()
        .filter(fact -> fact.evidenceBlockIds().contains(sourceBlockId))
        .map(fact -> normalize(fact.factText()))
        .anyMatch(
            fact -> fact.equals(candidate) || fact.contains(candidate) || candidate.contains(fact));
  }

  private FactProposalSet extractFacts(
      DocumentUnit unit,
      List<RawBlock> blocks,
      List<String> focusSections,
      List<MissingFact> missing) {
    List<FactProposal> facts = new ArrayList<>();
    for (List<RawBlock> batch : factExtractionBatches(blocks)) {
      FactProposalSet result = extractFactsBatchResilient(unit, batch, focusSections, missing);
      if (result != null && result.facts() != null) facts.addAll(result.facts());
    }
    return new FactProposalSet(List.copyOf(facts));
  }

  /**
   * A large extraction prompt can receive an HTTP 200 response with no message content from the
   * provider. Retry that transport-shape failure by splitting only at complete RawBlock boundaries;
   * if one block fragment is still too large, split that prompt-only representation again without
   * changing the persisted RawBlock.
   */
  private FactProposalSet extractFactsBatchResilient(
      DocumentUnit unit,
      List<RawBlock> blocks,
      List<String> focusSections,
      List<MissingFact> missing) {
    return extractFactsBatchResilient(unit, blocks, focusSections, missing, 0);
  }

  private FactProposalSet extractFactsBatchResilient(
      DocumentUnit unit,
      List<RawBlock> blocks,
      List<String> focusSections,
      List<MissingFact> missing,
      int splitDepth) {
    try {
      return model.generateStructured(
          ModelRole.LARGE,
          ModelTask.SUMMARY,
          "你是企业知识摘要事实提取器。只能从给定 RAW BLOCKS 提取事实。每条事实必须给出真实 evidence_block_ids。"
              + "section 不是固定模板字段，应根据原文内容自然归类为简洁、稳定、可读的主题名称；不得为了凑结构虚构 section、事实或结论。"
              + "只有在 evidence_block_ids 对应的完整原文中能够直接找到或明确推出的原子事实才可输出；标题、页码、目录、排版标记和无法由原文直接支持的推测一律跳过。"
              + "如果不确定是否有直接证据就不要输出，宁可少提取，也不能输出无证据或需要常识补全的事实。",
          "document_type_hint="
              + unit.documentType()
              + "\nfocus_sections="
              + focusSections
              + "\nmissing="
              + missing
              + "\nRAW BLOCKS:\n"
              + renderBlocks(blocks),
          FactProposalSet.class);
    } catch (RuntimeException failure) {
      if (!summaryProviderSplitFailure(failure, ModelTask.SUMMARY)
          || splitDepth >= MAX_PROVIDER_SPLIT_DEPTH) throw failure;
      if (blocks.size() == 1) {
        List<RawBlock> fragments = splitRawBlockForProvider(blocks.get(0));
        if (fragments.size() > 1) {
          List<FactProposal> facts = new ArrayList<>();
          for (RawBlock fragment : fragments) {
            FactProposalSet result =
                extractFactsBatchResilient(
                    unit, List.of(fragment), focusSections, missing, splitDepth + 1);
            if (result != null && result.facts() != null) facts.addAll(result.facts());
          }
          return new FactProposalSet(List.copyOf(facts));
        }
        throw failure;
      }
      int midpoint = blocks.size() / 2;
      FactProposalSet left =
          extractFactsBatchResilient(
              unit, blocks.subList(0, midpoint), focusSections, missing, splitDepth + 1);
      FactProposalSet right =
          extractFactsBatchResilient(
              unit,
              blocks.subList(midpoint, blocks.size()),
              focusSections,
              missing,
              splitDepth + 1);
      List<FactProposal> facts = new ArrayList<>();
      if (left != null && left.facts() != null) facts.addAll(left.facts());
      if (right != null && right.facts() != null) facts.addAll(right.facts());
      return new FactProposalSet(List.copyOf(facts));
    }
  }

  private List<SummaryFact> groundFacts(
      DocumentUnit unit,
      List<RawBlock> blocks,
      Map<String, Evidence> evidenceByBlock,
      List<FactProposal> proposals) {
    Map<String, RawBlock> blockById = new LinkedHashMap<>();
    blocks.forEach(block -> blockById.put(block.id(), block));
    Set<String> seen = new LinkedHashSet<>();
    if (proposals == null) return List.of();
    List<GroundingCandidate> candidates = new ArrayList<>();
    int candidateNo = 0;
    for (FactProposal proposed : proposals) {
      String section = trim(proposed.section());
      String text = trim(proposed.factText());
      if (section.isEmpty() || text.isEmpty()) continue;
      String dedupe = section + "\u0000" + normalize(text);
      if (!seen.add(dedupe)) continue;
      List<String> blockIds =
          proposed.evidenceBlockIds() == null
              ? List.of()
              : proposed.evidenceBlockIds().stream()
                  .map(SummaryCompilationService::trim)
                  .filter(id -> !id.isEmpty())
                  .distinct()
                  .toList();
      List<Evidence> evidence = new ArrayList<>();
      boolean valid = !blockIds.isEmpty();
      for (String blockId : blockIds) {
        if (!blockById.containsKey(blockId) || !evidenceByBlock.containsKey(blockId)) {
          valid = false;
          break;
        }
        evidence.add(evidenceByBlock.get(blockId));
      }
      String factId =
          deterministicUuid("summary_fact:" + unit.id() + ":" + section + ":" + normalize(text));
      candidates.add(
          new GroundingCandidate(
              "C" + (++candidateNo),
              factId,
              section,
              text,
              blockIds,
              List.copyOf(evidence),
              valid,
              valid ? "" : "unknown or missing evidence block"));
    }

    List<GroundingCandidate> validCandidates =
        candidates.stream().filter(GroundingCandidate::valid).toList();
    Map<String, SupportDecision> decisions = judgeGrounding(validCandidates);
    List<SummaryFact> facts = new ArrayList<>(candidates.size());
    for (GroundingCandidate candidate : candidates) {
      SupportDecision decision =
          candidate.valid()
              ? decisions.get(candidate.judgeId())
              : new SupportDecision(false, candidate.invalidReason());
      if (decision == null)
        throw new IllegalStateException(
            "summary grounding decision missing for " + candidate.judgeId());
      // A proposal rejected by the independent Grounding Judge is not part of the derived
      // summary at all. Keeping rejected text in an inactive artifact makes every transient model
      // over-proposal block an otherwise evidence-only summary; dropping it is still fail-closed
      // because no unsupported claim can reach Summary, Concept, Graph, or Wiki, while the
      // independent Coverage Judge continues to inspect every semantic RawBlock for omissions.
      if (!decision.supported()) continue;
      facts.add(
          new SummaryFact(
              candidate.factId(),
              "",
              candidate.section(),
              candidate.factText(),
              candidate.blockIds(),
              candidate.evidence().stream().map(Evidence::id).distinct().toList(),
              "PASS",
              trim(decision.reason())));
    }
    return List.copyOf(facts);
  }

  private Map<String, SupportDecision> judgeGrounding(List<GroundingCandidate> candidates) {
    if (candidates.isEmpty()) return Map.of();
    if (candidates.size() == 1) {
      GroundingCandidate candidate = candidates.get(0);
      SupportDecision decision = judgeGroundingCandidate(candidate);
      return Map.of(candidate.judgeId(), decision);
    }

    Map<String, SupportDecision> decisions = new LinkedHashMap<>();
    // Keep each structured response small enough for the local/provider context
    // window.  Every candidate is still judged exactly once and the aggregate
    // remains fail-closed: a missing, duplicate, or unknown decision aborts the
    // compilation rather than silently accepting an incomplete batch.
    for (int start = 0; start < candidates.size(); start += GROUNDING_JUDGE_BATCH_SIZE) {
      int end = Math.min(candidates.size(), start + GROUNDING_JUDGE_BATCH_SIZE);
      List<GroundingCandidate> batchCandidates = candidates.subList(start, end);
      Map<String, SupportDecision> batch;
      // A candidate carries the complete persisted Evidence text. A four-candidate envelope can
      // therefore exceed the remote gateway's reliable request size even when every individual
      // candidate is safe. Avoid spending a full timeout on that envelope and use the exact
      // candidate-by-candidate path, whose evidence is split without dropping source characters.
      if (renderGroundingCandidates(batchCandidates).length() > MODEL_PROMPT_FRAGMENT_CHARACTERS) {
        batch = judgeGroundingIndividually(batchCandidates);
      } else {
        try {
          batch = judgeGroundingBatch(batchCandidates);
        } catch (RuntimeException failure) {
          // Some OpenAI-compatible gateways occasionally return HTTP 200 without message content
          // or violate the batch candidate contract (for example by omitting C52) even though the
          // request itself succeeded. Keep the evidence and candidate set unchanged, but fall back
          // to one complete, independently judged request per candidate. Any individual failure,
          // unknown id, or missing decision still fails closed; this is deterministic re-grounding,
          // not an acceptance fallback for the malformed batch.
          if (!summaryProviderSplitFailure(failure, ModelTask.SUMMARY_JUDGE)) throw failure;
          batch = judgeGroundingIndividually(batchCandidates);
        }
      }
      for (Map.Entry<String, SupportDecision> entry : batch.entrySet()) {
        if (decisions.putIfAbsent(entry.getKey(), entry.getValue()) != null)
          throw new IllegalStateException(
              "summary grounding batch duplicated candidate " + entry.getKey());
      }
    }
    for (GroundingCandidate candidate : candidates)
      if (!decisions.containsKey(candidate.judgeId()))
        throw new IllegalStateException(
            "summary grounding batch missing candidate " + candidate.judgeId());
    return Map.copyOf(decisions);
  }

  private Map<String, SupportDecision> judgeGroundingIndividually(
      List<GroundingCandidate> candidates) {
    Map<String, SupportDecision> decisions = new LinkedHashMap<>();
    for (GroundingCandidate candidate : candidates) {
      SupportDecision decision = judgeGroundingCandidate(candidate);
      if (decision == null)
        throw new IllegalStateException(
            "summary grounding individual judge returned empty decision for "
                + candidate.judgeId());
      decisions.put(candidate.judgeId(), decision);
    }
    return Map.copyOf(decisions);
  }

  /**
   * Judge one candidate without sending an oversized Evidence payload to the provider. A large
   * Evidence value is split into complete row/newline fragments for independent checks; a single
   * supported fragment is sufficient only when the judge directly supports the FACT from that
   * fragment. Any transport/decode failure still propagates and therefore remains fail-closed.
   */
  private SupportDecision judgeGroundingCandidate(GroundingCandidate candidate) {
    List<String> evidenceFragments =
        splitModelText(
            renderEvidence(candidate.evidence()), GROUNDING_EVIDENCE_FRAGMENT_CHARACTERS);
    if (evidenceFragments.isEmpty())
      throw new IllegalStateException(
          "summary grounding candidate has empty evidence " + candidate.judgeId());
    SupportDecision supported = null;
    for (String evidence : evidenceFragments) {
      SupportDecision decision =
          model.generateStructured(
              ModelRole.SMALL,
              ModelTask.SUMMARY_JUDGE,
              "你是独立事实 Grounding Judge。仅判断 FACT 是否被给定 RAW EVIDENCE 直接支持。"
                  + "EVIDENCE 可能是同一原始证据的完整分片；只依据当前分片，严禁使用常识补全。"
                  + "严格输出 supported/reason。",
              "FACT: " + candidate.factText() + "\nEVIDENCE:\n" + evidence,
              SupportDecision.class);
      if (decision == null)
        throw new IllegalStateException(
            "summary grounding individual judge returned empty decision for "
                + candidate.judgeId());
      if (decision.supported()) supported = decision;
    }
    return supported == null
        ? new SupportDecision(false, "no evidence fragment directly supports the fact")
        : supported;
  }

  private static boolean missingResponseContent(Throwable failure, ModelTask task) {
    Throwable current = failure;
    String expected = "chat response missing content for " + task;
    while (current != null) {
      String message = current.getMessage();
      if (message != null && message.contains(expected)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  /**
   * A 524 is a provider/gateway timeout, not a business or structured-output rejection. For summary
   * extraction and coverage, retrying the exact oversized request is unlikely to help, so the
   * resilient callers may split it at complete RawBlock boundaries. A single-block request still
   * fails closed because there is no safe source-preserving split left.
   */
  private static boolean gatewayTimeoutFailure(Throwable failure, ModelTask task) {
    String expected = ("chat request failed for " + task).toLowerCase();
    for (Throwable current = failure; current != null; current = current.getCause()) {
      String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
      if (message.contains(expected) && message.contains("http 524")) return true;
    }
    return false;
  }

  /**
   * Treat an explicit client/provider timeout as a safe request-size split signal for summary
   * prompts. The original RawBlock/Evidence is still fully covered by the resulting fragments;
   * unrelated business errors and decode failures continue to fail closed.
   */
  private static boolean requestTimeoutFailure(Throwable failure, ModelTask task) {
    String expected = ("chat request failed for " + task).toLowerCase();
    for (Throwable current = failure; current != null; current = current.getCause()) {
      String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
      String type = current.getClass().getSimpleName().toLowerCase();
      if (message.contains(expected)
          && (message.contains("httptimeoutexception")
              || message.contains("request timed out")
              || message.contains("timed out")
              || type.equals("httptimeoutexception"))) return true;
    }
    return false;
  }

  /**
   * The remote compatibility gateway also uses a plain HTTP 500 for an oversized structured
   * request. Treat only that explicit upstream-gateway message as a safe split signal; arbitrary
   * HTTP 500/business errors still fail closed. Splitting happens only at complete candidate/raw
   * block boundaries and never drops or truncates evidence.
   */
  private static boolean upstreamGatewayFailure(Throwable failure, ModelTask task) {
    String expected = ("chat request failed for " + task).toLowerCase();
    for (Throwable current = failure; current != null; current = current.getCause()) {
      String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
      if (message.contains(expected)
          && message.contains("http 500")
          && message.contains("upstream gateway error")) return true;
    }
    return false;
  }

  private static boolean summaryProviderSplitFailure(Throwable failure, ModelTask task) {
    return missingResponseContent(failure, task)
        || gatewayTimeoutFailure(failure, task)
        || upstreamGatewayFailure(failure, task)
        || requestTimeoutFailure(failure, task)
        || groundingBatchContractFailure(failure);
  }

  /**
   * A batch judge may return a syntactically valid JSON value while omitting, duplicating, or
   * inventing a candidate id. Re-issue the same candidates as independent requests so every
   * decision is still checked against exactly one fact/evidence pair. The individual path remains
   * fail-closed; this only prevents one malformed batch envelope from discarding an otherwise valid
   * document after hours of deterministic compilation.
   */
  private static boolean groundingBatchContractFailure(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
      if (message.contains("summary grounding batch missing candidate")
          || message.contains("summary grounding batch references unknown candidate")
          || message.contains("summary grounding batch duplicated candidate")
          || message.contains("summary grounding batch contains null decision")
          || message.contains("summary grounding batch returned empty decisions")) return true;
    }
    return false;
  }

  private Map<String, SupportDecision> judgeGroundingBatch(List<GroundingCandidate> candidates) {
    SupportDecisionSet result;
    try {
      result =
          model.generateStructured(
              ModelRole.SMALL,
              ModelTask.SUMMARY_JUDGE,
              "你是独立事实 Grounding Judge。逐条判断每个 candidate 是否被它自己的 RAW EVIDENCE 直接支持。"
                  + "必须为每个 candidate_id 恰好返回一条 decision，不得遗漏、增加或改写 candidate_id；禁止使用常识补全。",
              renderGroundingCandidates(candidates),
              SupportDecisionSet.class);
    } catch (RuntimeException failure) {
      throw new IllegalStateException(
          "summary grounding judge failed for "
              + candidates.size()
              + " candidates: "
              + failure.getMessage(),
          failure);
    }
    if (result == null || result.decisions() == null)
      throw new IllegalStateException("summary grounding batch returned empty decisions");
    Map<String, SupportDecision> decisions = new LinkedHashMap<>();
    Set<String> expected =
        candidates.stream()
            .map(GroundingCandidate::judgeId)
            .collect(java.util.stream.Collectors.toSet());
    for (SupportDecisionItem raw : result.decisions()) {
      if (raw == null)
        throw new IllegalStateException("summary grounding batch contains null decision");
      String candidateId = trim(raw.candidateId());
      if (!expected.contains(candidateId))
        throw new IllegalStateException(
            "summary grounding batch references unknown candidate " + candidateId);
      if (decisions.putIfAbsent(
              candidateId, new SupportDecision(raw.supported(), trim(raw.reason())))
          != null)
        throw new IllegalStateException(
            "summary grounding batch duplicated candidate " + candidateId);
    }
    for (String candidateId : expected)
      if (!decisions.containsKey(candidateId))
        throw new IllegalStateException("summary grounding batch missing candidate " + candidateId);
    return Map.copyOf(decisions);
  }

  private static String renderGroundingCandidates(List<GroundingCandidate> candidates) {
    StringBuilder out = new StringBuilder("CANDIDATES:\n");
    for (GroundingCandidate candidate : candidates) {
      out.append("candidate_id=")
          .append(candidate.judgeId())
          .append("\nFACT=")
          .append(candidate.factText())
          .append("\nEVIDENCE:\n")
          .append(renderEvidence(candidate.evidence()))
          .append("\n===\n");
    }
    return out.toString();
  }

  private static String renderEvidence(List<Evidence> evidence) {
    return evidence.stream().map(Evidence::content).reduce("", (a, b) -> a + "\n---\n" + b);
  }

  private CoverageDecision checkCoverage(
      DocumentUnit unit, List<RawBlock> blocks, List<SummaryFact> facts) {
    List<RawBlock> semanticBlocks = coverageEligibleBlocks(blocks);
    if (semanticBlocks.isEmpty()) return new CoverageDecision(List.of());
    List<MissingFact> missing = new ArrayList<>();
    for (List<RawBlock> batch : coverageBatches(semanticBlocks)) {
      CoverageDecision decision = checkCoverageBatchResilient(unit, batch, facts);
      if (decision.missing() != null) missing.addAll(decision.missing());
    }
    return new CoverageDecision(List.copyOf(missing));
  }

  /**
   * A provider can return an HTTP 200 response without message content for a larger Coverage
   * request. Split only that transport-shape failure into smaller complete-block requests; any
   * other failure, or an empty response for a single block, remains fail-closed.
   */
  private CoverageDecision checkCoverageBatchResilient(
      DocumentUnit unit, List<RawBlock> blocks, List<SummaryFact> facts) {
    return checkCoverageBatchResilient(unit, blocks, facts, 0);
  }

  private CoverageDecision checkCoverageBatchResilient(
      DocumentUnit unit, List<RawBlock> blocks, List<SummaryFact> facts, int splitDepth) {
    try {
      return checkCoverageBatch(unit, blocks, facts);
    } catch (RuntimeException failure) {
      if (!summaryProviderSplitFailure(failure, ModelTask.SUMMARY_JUDGE)
          || splitDepth >= MAX_PROVIDER_SPLIT_DEPTH) throw failure;
      // A single expanded fragment can still be too large for the provider. Split that same
      // persisted block representation again, preserving every character and the original block
      // id, before giving up. This path is entered only for an explicit provider timeout/empty
      // response; ordinary business or decode failures remain fail-closed.
      if (blocks.size() == 1) {
        List<RawBlock> fragments = splitRawBlockForProvider(blocks.get(0));
        if (fragments.size() > 1) {
          List<MissingFact> missing = new ArrayList<>();
          for (RawBlock fragment : fragments) {
            CoverageDecision decision =
                checkCoverageBatchResilient(unit, List.of(fragment), facts, splitDepth + 1);
            if (decision.missing() != null) missing.addAll(decision.missing());
          }
          return new CoverageDecision(List.copyOf(missing));
        }
        throw failure;
      }
      int midpoint = blocks.size() / 2;
      List<RawBlock> left = blocks.subList(0, midpoint);
      List<RawBlock> right = blocks.subList(midpoint, blocks.size());
      CoverageDecision leftDecision =
          checkCoverageBatchResilient(unit, left, facts, splitDepth + 1);
      CoverageDecision rightDecision =
          checkCoverageBatchResilient(unit, right, facts, splitDepth + 1);
      List<MissingFact> missing = new ArrayList<>();
      if (leftDecision.missing() != null) missing.addAll(leftDecision.missing());
      if (rightDecision.missing() != null) missing.addAll(rightDecision.missing());
      return new CoverageDecision(List.copyOf(missing));
    }
  }

  /**
   * Split one prompt-only RawBlock representation in half after an explicit provider timeout. The
   * returned fragments are never persisted or embedded; concatenating their content is exactly the
   * original block content and each fragment retains the source block id.
   */
  private static List<RawBlock> splitRawBlockForProvider(RawBlock block) {
    List<String> fragments =
        splitModelText(block.content(), Math.max(1, block.content().length() / 2));
    if (fragments.size() <= 1) return List.of(block);
    return fragments.stream()
        .map(
            fragment ->
                new RawBlock(
                    block.id(),
                    block.versionId(),
                    block.blockNo(),
                    block.blockType(),
                    fragment,
                    fragment,
                    block.headingPath(),
                    block.pageStart(),
                    block.pageEnd(),
                    block.bbox(),
                    block.pageSize(),
                    block.sourceBlockIds(),
                    sha256(fragment),
                    block.tokenCount()))
        .toList();
  }

  /**
   * Page numbers and parser-only header/footer markers are retained as Raw/Evidence, but are not
   * semantic facts that a Coverage Judge should require a summary to repeat.
   */
  private static List<RawBlock> coverageEligibleBlocks(List<RawBlock> blocks) {
    return blocks.stream().filter(block -> !structuralOnlyBlock(block)).toList();
  }

  private static boolean structuralOnlyBlock(RawBlock block) {
    String type = trim(block.blockType()).toLowerCase();
    if (type.equals("page_number")
        || type.equals("page-header")
        || type.equals("page_header")
        || type.equals("page-footer")
        || type.equals("page_footer")
        || type.equals("header")
        || type.equals("footer")) return true;
    String content = trim(block.content());
    // MinerU frequently labels a standalone heading as a text block. It is preserved in Raw and
    // Evidence, but it is not an independent fact that Coverage should require the Summary to
    // repeat. Compare against the final heading component instead of dropping all short text so
    // short but material clauses remain eligible.
    String heading = headingTitle(block);
    if (!heading.isEmpty() && normalize(content).equals(normalize(heading))) return true;
    return content.matches("^第[一二三四五六七八九十百千万0-9]+[章节条]$") || content.matches("^（[^。！？；]{0,24}）$");
  }

  private CoverageDecision checkCoverageBatch(
      DocumentUnit unit, List<RawBlock> blocks, List<SummaryFact> facts) {
    try {
      return model.generateStructured(
          ModelRole.SMALL,
          ModelTask.SUMMARY_JUDGE,
          "你是独立 Coverage Judge。只找 RAW BLOCKS 中确实存在、对理解该文档重要、但当前 PASS FACTS 未覆盖的事实。"
              + "不要按照固定模板检查栏目，也不要要求原文不存在的信息。制度或合同中的普通标题、页码、目录和逐条排版标记不是 missing；只有主体、数量、时间、金额、权限、义务或限制等对理解文档有实质影响的事实才可列出。"
              + "如果 PASS FACTS 已经以不同措辞覆盖同一原文语义，不要重复列出；不确定时返回空 missing。每个 missing 必须给 source_block_ids，并为该事实自然命名一个简洁 section。",
          "document_type_hint="
              + unit.documentType()
              + "\nPASS FACTS="
              + facts
              + "\nRAW BLOCKS:\n"
              + renderBlocks(blocks),
          CoverageDecision.class);
    } catch (RuntimeException failure) {
      throw new IllegalStateException(
          "summary coverage judge failed for " + blocks.size() + " blocks: " + failure.getMessage(),
          failure);
    }
  }

  private static List<List<RawBlock>> coverageBatches(List<RawBlock> blocks) {
    return batchesByRenderedCharacters(
        expandOversizedPromptBlocks(blocks), COVERAGE_JUDGE_BATCH_CHARACTERS);
  }

  private static List<List<RawBlock>> factExtractionBatches(List<RawBlock> blocks) {
    return batchesByRenderedCharacters(
        expandOversizedPromptBlocks(blocks), FACT_EXTRACTION_BATCH_CHARACTERS);
  }

  /**
   * Expand only oversized prompt representations. Every fragment keeps the original block id so
   * model proposals still resolve to the persisted RawBlock/Evidence; no fragment is persisted or
   * used as an embedding input.
   */
  private static List<RawBlock> expandOversizedPromptBlocks(List<RawBlock> blocks) {
    List<RawBlock> expanded = new ArrayList<>();
    for (RawBlock block : blocks) {
      List<String> fragments = splitModelText(block.content(), MODEL_PROMPT_FRAGMENT_CHARACTERS);
      if (fragments.size() == 1) {
        expanded.add(block);
        continue;
      }
      for (String fragment : fragments) {
        expanded.add(
            new RawBlock(
                block.id(),
                block.versionId(),
                block.blockNo(),
                block.blockType(),
                fragment,
                fragment,
                block.headingPath(),
                block.pageStart(),
                block.pageEnd(),
                block.bbox(),
                block.pageSize(),
                block.sourceBlockIds(),
                sha256(fragment),
                block.tokenCount()));
      }
    }
    return List.copyOf(expanded);
  }

  /**
   * Split text without dropping, shortening, or inserting an ellipsis. Prefer HTML table row and
   * newline boundaries; fall back to an exact character boundary only when no safe boundary is
   * available. The method is used solely to bound remote model prompts.
   */
  private static List<String> splitModelText(String text, int maxCharacters) {
    String value = text == null ? "" : text;
    if (value.isEmpty()) return List.of();
    if (maxCharacters <= 0 || value.length() <= maxCharacters) return List.of(value);
    List<String> fragments = new ArrayList<>();
    int start = 0;
    while (start < value.length()) {
      int end = Math.min(value.length(), start + maxCharacters);
      if (end < value.length()) {
        int rowEnd = value.lastIndexOf("</tr>", end);
        if (rowEnd >= start + Math.max(1, maxCharacters / 2)) {
          end = rowEnd + "</tr>".length();
        } else {
          int newline = value.lastIndexOf('\n', end);
          if (newline >= start + Math.max(1, maxCharacters / 2)) end = newline + 1;
        }
      }
      if (end <= start) end = Math.min(value.length(), start + maxCharacters);
      if (end < value.length() && end > start && Character.isHighSurrogate(value.charAt(end - 1)))
        end--;
      if (end <= start) end = Math.min(value.length(), start + maxCharacters);
      fragments.add(value.substring(start, end));
      start = end;
    }
    return List.copyOf(fragments);
  }

  private static List<List<RawBlock>> batchesByRenderedCharacters(
      List<RawBlock> blocks, int maxCharacters) {
    List<List<RawBlock>> batches = new ArrayList<>();
    List<RawBlock> current = new ArrayList<>();
    int characters = 0;
    for (RawBlock block : blocks) {
      int blockCharacters = renderBlocks(List.of(block)).length();
      if (!current.isEmpty() && characters + blockCharacters > maxCharacters) {
        batches.add(List.copyOf(current));
        current = new ArrayList<>();
        characters = 0;
      }
      current.add(block);
      characters += blockCharacters;
    }
    if (!current.isEmpty()) batches.add(List.copyOf(current));
    return List.copyOf(batches);
  }

  private List<DocumentUnit> normalizeClassification(
      VersionSource source, Classification classification) {
    if (classification == null)
      throw new IllegalStateException("summary classification returned empty output");
    String manual = trim(source.manualDocumentType());
    String structure = trim(classification.structureType()).toLowerCase();
    if (!STRUCTURES.contains(structure))
      throw new IllegalStateException("unsupported structure type: " + structure);
    int min = source.blocks().stream().mapToInt(RawBlock::blockNo).min().orElseThrow();
    int max = source.blocks().stream().mapToInt(RawBlock::blockNo).max().orElseThrow();
    Set<Integer> available = new LinkedHashSet<>();
    source.blocks().forEach(b -> available.add(b.blockNo()));
    if (structure.equals("mixed")) {
      String type = manual.isEmpty() ? documentType(classification.documentType()) : manual;
      return List.of(unit(source.versionId(), 1, "", "mixed", type, min, max));
    }
    if (structure.equals("single")) {
      String type = manual.isEmpty() ? documentType(classification.documentType()) : manual;
      return List.of(unit(source.versionId(), 1, "", "single", type, min, max));
    }
    List<UnitProposal> proposals =
        classification.units() == null ? List.of() : new ArrayList<>(classification.units());
    if (proposals.isEmpty()) throw new IllegalStateException("bundle has no units");
    proposals.sort(
        Comparator.comparingInt(UnitProposal::startBlockNo)
            .thenComparingInt(UnitProposal::endBlockNo));
    List<DocumentUnit> units = new ArrayList<>();
    Set<Integer> covered = new LinkedHashSet<>();
    int lastEnd = min - 1;
    for (int i = 0; i < proposals.size(); i++) {
      UnitProposal p = proposals.get(i);
      if (p.startBlockNo() > p.endBlockNo()
          || p.startBlockNo() < min
          || p.endBlockNo() > max
          || p.startBlockNo() <= lastEnd) {
        throw new IllegalStateException(
            "invalid or overlapping bundle unit range " + p.startBlockNo() + "-" + p.endBlockNo());
      }
      boolean containsRawBlock =
          available.stream().anyMatch(n -> n >= p.startBlockNo() && n <= p.endBlockNo());
      if (!containsRawBlock)
        throw new IllegalStateException(
            "bundle unit range contains no raw block " + p.startBlockNo() + "-" + p.endBlockNo());
      available.stream()
          .filter(n -> n >= p.startBlockNo() && n <= p.endBlockNo())
          .forEach(covered::add);
      String type = manual.isEmpty() ? documentType(p.documentType()) : manual;
      units.add(
          unit(
              source.versionId(),
              i + 1,
              trim(p.title()),
              "bundle",
              type,
              p.startBlockNo(),
              p.endBlockNo()));
      lastEnd = p.endBlockNo();
    }
    if (covered.size() != available.size()) {
      Set<Integer> uncovered = new LinkedHashSet<>(available);
      uncovered.removeAll(covered);
      throw new IllegalStateException("bundle leaves raw blocks uncovered " + uncovered);
    }
    return List.copyOf(units);
  }

  private static List<DocumentUnit> fallbackDocumentUnits(VersionSource source) {
    return fallbackDocumentUnits(source, FALLBACK_UNIT_TARGET_CHARACTERS);
  }

  private static List<DocumentUnit> fallbackDocumentUnits(
      VersionSource source, int targetCharacters) {
    List<RawBlock> blocks =
        source.blocks().stream().sorted(Comparator.comparingInt(RawBlock::blockNo)).toList();
    if (blocks.isEmpty()) throw new IllegalStateException("summary fallback has no raw blocks");

    List<FallbackRange> ranges = new ArrayList<>();
    int startIndex = 0;
    int characters = 0;
    int headingBreakMinimum =
        targetCharacters <= FALLBACK_UNIT_TARGET_CHARACTERS
            ? FALLBACK_HEADING_BREAK_MIN_CHARACTERS
            : Math.max(FALLBACK_HEADING_BREAK_MIN_CHARACTERS, targetCharacters / 2);
    String currentHeading = headingTitle(blocks.get(0));
    for (int i = 0; i < blocks.size(); i++) {
      RawBlock block = blocks.get(i);
      String heading = headingTitle(block);
      int nextCharacters = characters + block.content().length();
      boolean headingBreak =
          i > startIndex
              && characters >= headingBreakMinimum
              && !heading.isEmpty()
              && !heading.equals(currentHeading);
      boolean sizeBreak = i > startIndex && nextCharacters > targetCharacters;
      if (headingBreak || sizeBreak) {
        ranges.add(range(blocks, startIndex, i - 1, currentHeading));
        startIndex = i;
        characters = 0;
        currentHeading = heading;
      } else if (currentHeading.isEmpty() && !heading.isEmpty()) {
        currentHeading = heading;
      }
      characters += block.content().length();
    }
    ranges.add(range(blocks, startIndex, blocks.size() - 1, currentHeading));

    String manualType = trim(source.manualDocumentType());
    String metadataType = source.metadata() == null ? "" : trim(source.metadata().documentType());
    String type = !manualType.isEmpty() ? manualType : documentType(metadataType);
    String structure = ranges.size() == 1 ? "single" : "bundle";
    List<DocumentUnit> units = new ArrayList<>(ranges.size());
    for (int i = 0; i < ranges.size(); i++) {
      FallbackRange range = ranges.get(i);
      String title =
          range.title().isEmpty()
              ? source.logicalName() + (ranges.size() == 1 ? "" : " 第" + (i + 1) + "部分")
              : range.title();
      units.add(
          unit(
              source.versionId(),
              i + 1,
              title,
              structure,
              type,
              range.startBlockNo(),
              range.endBlockNo()));
    }
    return List.copyOf(units);
  }

  private static FallbackRange range(
      List<RawBlock> blocks, int startIndex, int endIndex, String title) {
    return new FallbackRange(
        blocks.get(startIndex).blockNo(), blocks.get(endIndex).blockNo(), trim(title));
  }

  private static String headingTitle(RawBlock block) {
    if (block.headingPath() == null) return "";
    for (int i = block.headingPath().size() - 1; i >= 0; i--) {
      String heading = trim(block.headingPath().get(i));
      if (!heading.isEmpty()) return heading;
    }
    return "";
  }

  private static boolean transientClassificationFailure(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
      if (message.contains("cannot decode structured model output")) return false;
    }
    for (Throwable current = failure; current != null; current = current.getCause()) {
      String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
      if (message.contains("http 429")
          || message.contains("http 502")
          || message.contains("http 503")
          || message.contains("http 504")
          || message.contains("http 524")
          || message.contains("httptimeoutexception")
          || message.contains("connectexception")
          || message.contains("timed out")
          || message.contains("timeout")) return true;
      String type = current.getClass().getSimpleName();
      if (type.equals("HttpTimeoutException") || type.equals("ConnectException")) return true;
    }
    return false;
  }

  private static DocumentUnit unit(
      String versionId, int no, String title, String structure, String type, int start, int end) {
    return new DocumentUnit(
        deterministicUuid("unit:" + versionId + ":" + no + ":" + start + ":" + end + ":" + type),
        versionId,
        no,
        title,
        structure,
        type,
        start,
        end);
  }

  private static List<MissingFact> normalizeMissing(
      List<RawBlock> blocks, List<MissingFact> missing) {
    if (missing == null) return List.of();
    Set<String> blockIds = new LinkedHashSet<>();
    blocks.forEach(b -> blockIds.add(b.id()));
    List<MissingFact> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (MissingFact m : missing) {
      String section = trim(m.section()), description = trim(m.missingFactCandidate());
      if (section.isEmpty() || description.isEmpty()) continue;
      List<String> sources =
          m.sourceBlockIds() == null
              ? List.of()
              : m.sourceBlockIds().stream()
                  .map(SummaryCompilationService::trim)
                  .filter(blockIds::contains)
                  .distinct()
                  .toList();
      if (sources.isEmpty()) continue;
      if (seen.add(section + "\u0000" + normalize(description)))
        out.add(new MissingFact(description, section, sources));
    }
    return List.copyOf(out);
  }

  private static List<SummaryFact> mergeFacts(List<SummaryFact> a, List<SummaryFact> b) {
    List<SummaryFact> out = new ArrayList<>(a);
    Set<String> seen = new LinkedHashSet<>();
    a.forEach(f -> seen.add(f.section() + "\u0000" + normalize(f.factText())));
    for (SummaryFact f : b)
      if (seen.add(f.section() + "\u0000" + normalize(f.factText()))) out.add(f);
    return List.copyOf(out);
  }

  private static String coverageFingerprint(List<MissingFact> missing) {
    StringBuilder out = new StringBuilder();
    for (MissingFact candidate : missing) {
      out.append(normalize(candidate.section()))
          .append('\u0000')
          .append(normalize(candidate.missingFactCandidate()))
          .append('\u0000');
      if (candidate.sourceBlockIds() != null)
        candidate.sourceBlockIds().stream()
            .map(SummaryCompilationService::trim)
            .filter(id -> !id.isEmpty())
            .sorted()
            .forEach(id -> out.append(id).append(','));
      out.append(';');
    }
    return out.toString();
  }

  private static List<SummaryFact> directEvidenceFactsForMissing(
      DocumentUnit unit,
      List<RawBlock> blocks,
      Map<String, Evidence> evidenceByBlock,
      List<MissingFact> missing,
      List<SummaryFact> existing) {
    if (missing == null || missing.isEmpty()) return List.of();
    Map<String, RawBlock> byId = new LinkedHashMap<>();
    for (RawBlock block : blocks) byId.put(block.id(), block);
    Set<String> covered = new LinkedHashSet<>();
    for (SummaryFact fact : existing) covered.addAll(fact.evidenceBlockIds());
    Set<String> projected = new LinkedHashSet<>();
    List<SummaryFact> out = new ArrayList<>();
    for (MissingFact candidate : missing) {
      if (candidate.sourceBlockIds() == null) continue;
      String section = trim(candidate.section());
      for (String rawId : candidate.sourceBlockIds()) {
        String blockId = trim(rawId);
        RawBlock block = byId.get(blockId);
        Evidence evidence = evidenceByBlock.get(blockId);
        if (block == null
            || evidence == null
            || covered.contains(blockId)
            || !projected.add(blockId)
            || structuralOnlyBlock(block)) continue;
        String content = trim(block.content());
        if (content.isEmpty() || content.matches("^[0-9]+$")) continue;
        String factSection = section.isEmpty() ? headingTitle(block) : section;
        if (factSection.isEmpty()) factSection = "原文事实";
        out.add(
            new SummaryFact(
                deterministicUuid("summary_fact_direct:" + unit.id() + ":" + blockId),
                "",
                factSection,
                content,
                List.of(blockId),
                List.of(evidence.id()),
                "PASS",
                "direct raw evidence projection"));
      }
    }
    return List.copyOf(out);
  }

  private static List<SummaryFact> passing(List<SummaryFact> facts) {
    return facts.stream().filter(f -> "PASS".equals(f.groundingStatus())).toList();
  }

  private static String render(String logicalName, DocumentUnit unit, List<SummaryFact> facts) {
    StringBuilder out =
        new StringBuilder("# ")
            .append(unit.title().isBlank() ? logicalName : unit.title())
            .append("\n\n");
    LinkedHashSet<String> sections = new LinkedHashSet<>();
    for (SummaryFact fact : facts)
      if ("PASS".equals(fact.groundingStatus()) && !trim(fact.section()).isEmpty())
        sections.add(trim(fact.section()));
    for (String section : sections) {
      out.append("## ").append(section).append("\n\n");
      for (SummaryFact fact : facts)
        if ("PASS".equals(fact.groundingStatus()) && section.equals(trim(fact.section())))
          out.append("- ").append(fact.factText()).append("\n");
      out.append('\n');
    }
    return out.toString();
  }

  private static String renderBlocks(List<RawBlock> blocks) {
    StringBuilder out = new StringBuilder();
    for (RawBlock block : blocks)
      out.append('[')
          .append(block.id())
          .append(" | block_no=")
          .append(block.blockNo())
          .append("] ")
          .append(block.content())
          .append("\n");
    return out.toString();
  }

  private static String classificationSystemPrompt() {
    return "你是企业文档单元分类器。structure_type 只能是 single/bundle/mixed；document_type 是对文档内容的简洁描述，不受固定枚举限制。"
        + "bundle 的 unit 边界必须使用现有 block_no，不能重叠；mixed 表示无法安全拆分的混合材料。";
  }

  private static String classificationUserPrompt(VersionSource source) {
    return "manual_document_type_hint="
        + source.manualDocumentType()
        + "\nRAW BLOCKS:\n"
        + renderBlocks(source.blocks());
  }

  private static String documentType(String value) {
    String type = trim(value);
    return type.isEmpty() ? GENERAL : type;
  }

  private static String normalize(String value) {
    return trim(value).replaceAll("\\s+", " ").toLowerCase();
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static List<String> values(String value) {
    return value == null || value.isBlank() ? List.of() : List.of(value.trim());
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " is required");
    return value;
  }

  public record PreparedSummary(
      DocumentUnit unit, SummaryRecord summary, int coverageRepairCount) {}

  public record Classification(
      String documentType, String structureType, List<UnitProposal> units) {}

  public record UnitProposal(String title, String documentType, int startBlockNo, int endBlockNo) {}

  public record FactProposalSet(List<FactProposal> facts) {}

  public record FactProposal(String section, String factText, List<String> evidenceBlockIds) {}

  public record SupportDecision(boolean supported, String reason) {}

  public record SupportDecisionItem(String candidateId, boolean supported, String reason) {}

  public record SupportDecisionSet(List<SupportDecisionItem> decisions) {}

  private record GroundingCandidate(
      String judgeId,
      String factId,
      String section,
      String factText,
      List<String> blockIds,
      List<Evidence> evidence,
      boolean valid,
      String invalidReason) {}

  public record CoverageDecision(List<MissingFact> missing) {}

  public record MissingFact(
      String missingFactCandidate, String section, List<String> sourceBlockIds) {}

  private record FallbackRange(int startBlockNo, int endBlockNo, String title) {}
}
