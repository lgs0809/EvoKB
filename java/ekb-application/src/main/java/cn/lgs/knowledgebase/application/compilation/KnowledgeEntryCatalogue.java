package cn.lgs.knowledgebase.application.compilation;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.ConceptRecord;
import java.util.List;

/** Stable business entries; source Concepts remain separate immutable extraction provenance. */
public interface KnowledgeEntryCatalogue {
  /** Every model input, including candidate metadata not selected as a cited source. */
  record BuildInput(String summaryId, String versionId, long aclVersion) {}

  record Source(
      String documentId,
      String versionId,
      String summaryId,
      String conceptId,
      long aclVersion,
      List<String> factIds) {
    public Source {
      factIds = List.copyOf(factIds);
    }
  }

  record Entry(
      String id,
      long revision,
      String scopeKey,
      java.util.Map<String, Object> scopeAttributes,
      String type,
      String label,
      List<String> aliases,
      String description,
      String stage,
      String lifecycle,
      List<Source> sources,
      List<BuildInput> buildInputs) {
    public Entry {
      aliases = List.copyOf(aliases);
      scopeAttributes = java.util.Map.copyOf(scopeAttributes);
      sources = List.copyOf(sources);
      buildInputs = List.copyOf(buildInputs);
    }

    public List<String> summaryIds() {
      return sources.stream().map(Source::summaryId).distinct().sorted().toList();
    }

    public List<String> versionIds() {
      return buildInputs.stream().map(BuildInput::versionId).distinct().sorted().toList();
    }

    public List<String> inputSummaryIds() {
      return buildInputs.stream().map(BuildInput::summaryId).distinct().sorted().toList();
    }
  }

  record Publication(Entry entry, long expectedRevision) {}

  /** The model view can omit obsolete prose while retaining the exact frozen identity snapshot. */
  record Candidate(
      Entry snapshot,
      String label,
      List<String> aliases,
      String description,
      List<BuildInput> inputs,
      boolean completeMetadata) {
    public Candidate {
      aliases = List.copyOf(aliases);
      inputs = List.copyOf(inputs);
    }

    public String id() {
      return snapshot.id();
    }

    public List<String> versionIds() {
      return inputs.stream().map(BuildInput::versionId).distinct().sorted().toList();
    }
  }

  /** Only current, fully accessible source dependencies may enter the frozen model candidates. */
  List<Candidate> candidates(String sourceSummaryId, int limit);

  /** Grounded business scopes mentioned by a newly committed source, before heat selection. */
  List<Entry> relatedCandidates(String sourceVersionId, int limit);

  Publication prepare(
      ConceptRecord incoming,
      GraphScopeIdentity.Scope verifiedScope,
      String matchedEntryId,
      List<Candidate> frozenCandidates);

  /**
   * Called inside the compilation ownership/source publication fence, never across a model call.
   */
  void publish(Publication publication);

  java.util.Optional<Entry> find(String entryId);

  /** Current Concept-stage projections containing this grounded source Summary. */
  List<Entry> conceptEntriesForSummary(String summaryId);

  void wikiPublished(Entry frozenEntry);

  void graphPublished(Entry frozenEntry);

  /** Store evidence-backed pending membership and block known-incomplete prior conclusions. */
  void relatedSource(
      Entry frozenEntry, String sourceVersionId, List<String> evidenceIds, String literalImpact);
}
