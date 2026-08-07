package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.ConceptRecord;
import cn.lgs.knowledgebase.application.compilation.GraphCompilationService;
import cn.lgs.knowledgebase.application.compilation.GraphScopeIdentity;
import cn.lgs.knowledgebase.application.compilation.KnowledgeEntryCatalogue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Short native transactions for scoped identity and cross-source provenance; no model calls. */
public final class JdbcKnowledgeEntryCatalogue implements KnowledgeEntryCatalogue {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper mapper;
  private final String knowledgeBaseId;

  public JdbcKnowledgeEntryCatalogue(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      ObjectMapper mapper,
      String knowledgeBaseId) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transactions = Objects.requireNonNull(transactions);
    this.mapper = Objects.requireNonNull(mapper);
    if (knowledgeBaseId == null || knowledgeBaseId.isBlank())
      throw new IllegalArgumentException("trusted entry knowledge base is required");
    this.knowledgeBaseId = knowledgeBaseId;
  }

  @Override
  public List<Candidate> candidates(String sourceSummaryId, int limit) {
    String text =
        jdbc.queryForObject(
            "SELECT string_agg(fact_text,E'\\n' ORDER BY fact_id) FROM summary_fact WHERE summary_id=? AND grounding_status='PASS'",
            String.class,
            sourceSummaryId);
    String document =
        jdbc.queryForObject(
            "SELECT v.document_id FROM summary s JOIN document_version v ON v.version_id=s.version_id WHERE s.summary_id=?",
            String.class,
            sourceSummaryId);
    if (text == null || text.isBlank()) return List.of();
    String normalized = GraphCompilationService.canonicalKey(text);
    var ids =
        jdbc.query(
            "SELECT e.entry_id FROM kb_knowledge_entry e WHERE e.knowledge_base_id=? AND e.lifecycle IN ('ACTIVE','STALE') "
                + "AND EXISTS(SELECT 1 FROM kb_entry_source s WHERE s.knowledge_base_id=e.knowledge_base_id AND s.entry_id=e.entry_id) "
                + "AND ((e.scope_json->>'identity_status'='SOURCE_SCOPED' AND e.scope_json->>'document_id'=?) "
                + "OR (e.scope_json->>'identity_status'='SCOPE_GROUNDED' AND jsonb_array_length(e.scope_json->'anchors')>0 "
                + "AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(e.scope_json->'anchors') anchor WHERE strpos(?,anchor->>'value')=0))) "
                + "ORDER BY CASE WHEN strpos(?,lower(e.preferred_label))>0 THEN 0 ELSE 1 END,e.updated_at DESC,e.entry_id LIMIT ?",
            (rs, n) -> rs.getString(1),
            knowledgeBaseId,
            document,
            normalized,
            normalized,
            Math.max(1, Math.min(limit, 24)));
    var result = new ArrayList<Candidate>();
    for (String id : ids) {
      var entry = find(id).orElseThrow();
      boolean complete =
          !entry.buildInputs().isEmpty()
              && entry.buildInputs().stream().allMatch(this::currentInput)
              && entry.sources().stream().allMatch(this::currentSource);
      if (complete)
        result.add(
            new Candidate(
                entry,
                entry.label(),
                entry.aliases(),
                entry.description(),
                entry.buildInputs(),
                true));
      else {
        // Identity metadata is provable from this new source. Obsolete prose/aliases never enter
        // the model. Valid retained members are pinned before any external work; revoked ones go.
        var retained =
            entry.sources().stream()
                .filter(this::currentSource)
                .map(
                    source ->
                        new BuildInput(source.summaryId(), source.versionId(), source.aclVersion()))
                .distinct()
                .toList();
        String label = GraphCompilationService.canonicalKey(entry.label());
        String safeLabel = normalized.contains(label) ? entry.label() : entry.type();
        var aliases =
            entry.aliases().stream()
                .filter(alias -> normalized.contains(GraphCompilationService.canonicalKey(alias)))
                .toList();
        result.add(new Candidate(entry, safeLabel, aliases, "", retained, false));
      }
    }
    return List.copyOf(result);
  }

  private boolean currentInput(BuildInput input) {
    try {
      verifyBuildInput(input, false);
      return true;
    } catch (IllegalStateException stale) {
      return false;
    }
  }

  private boolean currentSource(Source source) {
    try {
      verifySource(source, false);
      return true;
    } catch (IllegalStateException stale) {
      return false;
    }
  }

  @Override
  public Optional<Entry> find(String id) {
    var entries =
        jdbc.query(
            "SELECT entry_id,revision,scope_key,scope_json::text,entry_type,preferred_label,aliases::text,description,stage,lifecycle FROM kb_knowledge_entry WHERE knowledge_base_id=? AND entry_id=?",
            (rs, n) ->
                new Entry(
                    rs.getString(1),
                    rs.getLong(2),
                    rs.getString(3),
                    objectMap(rs.getString(4)),
                    rs.getString(5),
                    rs.getString(6),
                    strings(rs.getString(7)),
                    rs.getString(8),
                    rs.getString(9),
                    rs.getString(10),
                    sources(id),
                    buildInputs(id, rs.getLong(2))),
            knowledgeBaseId,
            id);
    return entries.stream().findFirst();
  }

  @Override
  public List<Entry> conceptEntriesForSummary(String summaryId) {
    return jdbc
        .query(
            "SELECT DISTINCT e.entry_id FROM kb_knowledge_entry e JOIN kb_entry_source s USING(knowledge_base_id,entry_id) WHERE e.knowledge_base_id=? AND e.stage='CONCEPT' AND e.lifecycle='ACTIVE' AND s.summary_id=? ORDER BY e.entry_id",
            (rs, n) -> rs.getString(1),
            knowledgeBaseId,
            summaryId)
        .stream()
        .map(this::find)
        .flatMap(Optional::stream)
        .toList();
  }

  private List<Source> sources(String id) {
    return jdbc.query(
        "SELECT document_id,source_version_id,summary_id,concept_id,acl_version,fact_ids::text FROM kb_entry_source WHERE knowledge_base_id=? AND entry_id=? ORDER BY document_id,source_version_id,summary_id,concept_id",
        (rs, n) ->
            new Source(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getLong(5),
                strings(rs.getString(6))),
        knowledgeBaseId,
        id);
  }

  private List<BuildInput> buildInputs(String id, long revision) {
    return jdbc.query(
        "SELECT summary_id,source_version_id,acl_version FROM kb_entry_build_input WHERE knowledge_base_id=? AND entry_id=? AND entry_revision=? ORDER BY summary_id",
        (rs, n) -> new BuildInput(rs.getString(1), rs.getString(2), rs.getLong(3)),
        knowledgeBaseId,
        id,
        revision);
  }

  @Override
  public List<Entry> relatedCandidates(String versionId, int limit) {
    var raw =
        jdbc.queryForObject(
            "SELECT string_agg(raw_content,E'\\n' ORDER BY block_no) FROM raw_block WHERE version_id=?",
            String.class,
            versionId);
    if (raw == null || raw.isBlank()) return List.of();
    String text = GraphCompilationService.canonicalKey(raw);
    return jdbc
        .query(
            "SELECT e.entry_id FROM kb_knowledge_entry e WHERE e.knowledge_base_id=? AND e.lifecycle IN ('ACTIVE','STALE') "
                + "AND e.scope_json->>'identity_status'='SCOPE_GROUNDED' AND jsonb_array_length(e.scope_json->'anchors')>0 "
                + "AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(e.scope_json->'anchors') anchor WHERE strpos(?,anchor->>'value')=0) "
                + "AND EXISTS(SELECT 1 FROM kb_entry_build_input b WHERE b.knowledge_base_id=e.knowledge_base_id AND b.entry_id=e.entry_id AND b.entry_revision=e.revision) "
                + "AND NOT EXISTS(SELECT 1 FROM kb_entry_build_input b LEFT JOIN kb_source_authority a ON a.version_id=b.source_version_id "
                + "LEFT JOIN summary s ON s.summary_id=b.summary_id WHERE b.knowledge_base_id=e.knowledge_base_id AND b.entry_id=e.entry_id AND b.entry_revision=e.revision "
                + "AND (a.version_id IS NULL OR a.acl_version<>b.acl_version OR s.summary_id IS NULL OR s.version_id<>b.source_version_id OR s.lifecycle<>'ACTIVE' OR s.grounding_status<>'PASS' OR s.coverage_status<>'PASS')) "
                + "AND NOT EXISTS(SELECT 1 FROM kb_entry_source s WHERE s.knowledge_base_id=e.knowledge_base_id AND s.entry_id=e.entry_id AND s.source_version_id=?) "
                + "AND NOT EXISTS(SELECT 1 FROM kb_entry_pending_source p WHERE p.knowledge_base_id=e.knowledge_base_id AND p.entry_id=e.entry_id AND p.source_version_id=? AND p.associated_at IS NULL) "
                + "ORDER BY e.updated_at DESC,e.entry_id LIMIT ?",
            (rs, n) -> rs.getString(1),
            knowledgeBaseId,
            text,
            versionId,
            versionId,
            Math.max(1, Math.min(limit, 24)))
        .stream()
        .map(this::find)
        .flatMap(Optional::stream)
        .toList();
  }

  @Override
  public Publication prepare(
      ConceptRecord incoming,
      GraphScopeIdentity.Scope verifiedScope,
      String matchedEntryId,
      List<Candidate> frozenCandidates) {
    Objects.requireNonNull(incoming);
    Objects.requireNonNull(verifiedScope);
    String selected = matchedEntryId == null ? "" : matchedEntryId.trim();
    Entry previous = null;
    Candidate chosen = null;
    if (!selected.isBlank()) {
      chosen =
          frozenCandidates.stream()
              .filter(e -> e.id().equals(selected))
              .findFirst()
              .orElseThrow(
                  () -> new IllegalStateException("entry match is not a frozen candidate"));
      previous = chosen.snapshot();
      if (!previous.scopeKey().equals(verifiedScope.key())
          || !previous.type().equals(incoming.conceptTypeHint()))
        throw new IllegalStateException("entry match crosses a verified scope or semantic type");
      Entry actual =
          find(selected)
              .orElseThrow(() -> new IllegalStateException("entry candidate disappeared"));
      if (!actual.equals(previous))
        throw new IllegalStateException("entry candidate changed after model input");
    }
    String id =
        previous == null
            ? GraphCompilationService.stableId(
                "knowledge_entry",
                knowledgeBaseId,
                verifiedScope.key(),
                incoming.conceptTypeHint(),
                GraphCompilationService.canonicalKey(incoming.preferredLabel()))
            : previous.id();
    if (previous == null && find(id).isPresent())
      throw new IllegalStateException("existing entry must be matched from the frozen candidates");
    Source source = source(incoming);
    var inputs = new ArrayList<Source>();
    if (previous != null)
      for (var prior : previous.sources()) {
        // A new revision of one source replaces only that source's previous association.
        if (!prior.documentId().equals(source.documentId())
            || prior.versionId().equals(source.versionId())
                && !prior.summaryId().equals(source.summaryId())) {
          if (currentSource(prior)) inputs.add(prior);
        }
      }
    inputs.add(source);
    var aliases = new LinkedHashSet<String>();
    if (chosen != null) {
      aliases.addAll(chosen.aliases());
      if (chosen.completeMetadata()) aliases.add(previous.label());
    }
    aliases.addAll(incoming.aliases());
    aliases.remove(incoming.preferredLabel());
    long base = previous == null ? 0 : previous.revision();
    String stage = previous == null ? "CONCEPT" : previous.stage();
    var modelInputs = new java.util.TreeMap<String, BuildInput>();
    for (var candidate : frozenCandidates)
      for (var input : candidate.inputs()) {
        verifyBuildInput(input, false);
        modelInputs.put(input.summaryId(), input);
      }
    for (var input : inputs)
      modelInputs.put(
          input.summaryId(),
          new BuildInput(input.summaryId(), input.versionId(), input.aclVersion()));
    Entry result =
        new Entry(
            id,
            base + 1,
            verifiedScope.key(),
            verifiedScope.attributes(),
            incoming.conceptTypeHint(),
            incoming.preferredLabel(),
            List.copyOf(aliases),
            incoming.description(),
            stage,
            stage.equals("WIKI") ? "STALE" : "ACTIVE",
            List.copyOf(inputs),
            List.copyOf(modelInputs.values()));
    return new Publication(result, base);
  }

  private Source source(ConceptRecord concept) {
    var rows =
        jdbc.query(
            "SELECT a.document_id,s.version_id,a.acl_version FROM summary s JOIN kb_source_authority a ON a.version_id=s.version_id "
                + "WHERE s.summary_id=? AND s.version_id=? AND s.lifecycle='ACTIVE' AND s.grounding_status='PASS' AND s.coverage_status='PASS'",
            (rs, n) ->
                new Source(
                    rs.getString(1),
                    rs.getString(2),
                    concept.summaryId(),
                    concept.id(),
                    rs.getLong(3),
                    concept.factIds()),
            concept.summaryId(),
            concept.versionId());
    var source =
        rows.stream()
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("entry source is not current and grounded"));
    verifySource(source, false);
    return source;
  }

  private void verifySource(Source source, boolean lock) {
    var rows =
        jdbc.query(
            "SELECT a.acl_version FROM kb_source_authority a JOIN document_version v ON v.version_id=a.version_id "
                + "JOIN document d ON d.document_id=a.document_id JOIN document_metadata m ON m.version_id=a.version_id "
                + "JOIN summary s ON s.version_id=a.version_id WHERE a.version_id=? AND a.document_id=? AND s.summary_id=? "
                + "AND s.lifecycle='ACTIVE' AND s.grounding_status='PASS' AND s.coverage_status='PASS' "
                + (lock ? "FOR SHARE OF v,d,m,s" : ""),
            (rs, n) -> rs.getLong(1),
            source.versionId(),
            source.documentId(),
            source.summaryId());
    if (rows.size() != 1 || rows.get(0) != source.aclVersion() || source.factIds().isEmpty())
      throw new IllegalStateException("entry source permission/version changed");
    var facts = source.factIds().stream().distinct().sorted().toList();
    var args = new ArrayList<Object>();
    args.add(source.summaryId());
    args.addAll(facts);
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM summary_fact f WHERE summary_id=? AND fact_id IN ("
                + String.join(",", java.util.Collections.nCopies(facts.size(), "?"))
                + ") AND grounding_status='PASS' AND EXISTS(SELECT 1 FROM summary_evidence se JOIN evidence e ON e.evidence_id=se.evidence_id "
                + "WHERE se.fact_id=f.fact_id AND e.active AND e.version_id=?)",
            Integer.class,
            append(args, source.versionId()));
    if (count == null || count != facts.size())
      throw new IllegalStateException("entry source facts lack current raw evidence");
  }

  private static Object[] append(List<Object> args, Object last) {
    args.add(last);
    return args.toArray();
  }

  private void verifyBuildInput(BuildInput input, boolean lock) {
    var rows =
        jdbc.query(
            "SELECT a.acl_version FROM summary s JOIN kb_source_authority a ON a.version_id=s.version_id "
                + "JOIN document_version v ON v.version_id=a.version_id JOIN document d ON d.document_id=a.document_id JOIN document_metadata m ON m.version_id=a.version_id "
                + "WHERE s.summary_id=? AND s.version_id=? AND s.lifecycle='ACTIVE' AND s.grounding_status='PASS' AND s.coverage_status='PASS' "
                + (lock ? "FOR SHARE OF s,v,d,m" : ""),
            (rs, n) -> rs.getLong(1),
            input.summaryId(),
            input.versionId());
    if (rows.size() != 1 || rows.get(0) != input.aclVersion())
      throw new IllegalStateException("entry model input changed or was revoked");
  }

  @Override
  public void publish(Publication publication) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("entry publication requires the compilation transaction");
    var entry = publication.entry();
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", knowledgeBaseId + ":" + entry.id());
    var current = find(entry.id());
    if (current.map(Entry::revision).orElse(0L) != publication.expectedRevision())
      throw new IllegalStateException("entry revision changed before publication");
    entry.sources().forEach(s -> verifySource(s, true));
    if (entry.buildInputs().isEmpty())
      throw new IllegalStateException("entry model dependencies are absent");
    entry.buildInputs().forEach(s -> verifyBuildInput(s, true));
    jdbc.update(
        "INSERT INTO kb_knowledge_entry(knowledge_base_id,entry_id,revision,scope_key,scope_json,entry_type,preferred_label,aliases,description,stage,lifecycle) "
            + "VALUES(?,?,?,?,?::jsonb,?,?,?::jsonb,?,?,?) ON CONFLICT(knowledge_base_id,entry_id) DO UPDATE SET revision=EXCLUDED.revision,preferred_label=EXCLUDED.preferred_label,aliases=EXCLUDED.aliases,description=EXCLUDED.description,lifecycle=EXCLUDED.lifecycle,updated_at=clock_timestamp()",
        knowledgeBaseId,
        entry.id(),
        entry.revision(),
        entry.scopeKey(),
        json(entry.scopeAttributes()),
        entry.type(),
        entry.label(),
        json(entry.aliases()),
        entry.description(),
        entry.stage(),
        entry.lifecycle());
    for (var input : entry.buildInputs())
      jdbc.update(
          "INSERT INTO kb_entry_build_input(knowledge_base_id,entry_id,entry_revision,summary_id,source_version_id,acl_version) VALUES(?,?,?,?,?,?)",
          knowledgeBaseId,
          entry.id(),
          entry.revision(),
          input.summaryId(),
          input.versionId(),
          input.aclVersion());
    jdbc.update(
        "DELETE FROM kb_entry_source WHERE knowledge_base_id=? AND entry_id=?",
        knowledgeBaseId,
        entry.id());
    for (var source : entry.sources())
      jdbc.update(
          "INSERT INTO kb_entry_source(knowledge_base_id,entry_id,document_id,source_version_id,summary_id,concept_id,acl_version,fact_ids) VALUES(?,?,?,?,?,?,?,?::jsonb)",
          knowledgeBaseId,
          entry.id(),
          source.documentId(),
          source.versionId(),
          source.summaryId(),
          source.conceptId(),
          source.aclVersion(),
          json(source.factIds()));
    for (var source : entry.sources())
      jdbc.update(
          "UPDATE kb_entry_pending_source SET associated_at=clock_timestamp() WHERE knowledge_base_id=? AND entry_id=? AND source_version_id=? AND associated_at IS NULL",
          knowledgeBaseId,
          entry.id(),
          source.versionId());
    if (entry.lifecycle().equals("STALE"))
      jdbc.update(
          "UPDATE wiki SET lifecycle='STALE' WHERE wiki_id=? AND lifecycle='ACTIVE'", entry.id());
  }

  @Override
  public void wikiPublished(Entry frozenEntry) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("entry promotion requires the compilation transaction");
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        knowledgeBaseId + ":" + frozenEntry.id());
    var current =
        find(frozenEntry.id())
            .orElseThrow(
                () -> new IllegalStateException("entry disappeared during Wiki generation"));
    if (!current.equals(frozenEntry))
      throw new IllegalStateException("entry inputs changed during Wiki generation");
    frozenEntry.sources().forEach(s -> verifySource(s, true));
    frozenEntry.buildInputs().forEach(s -> verifyBuildInput(s, true));
    jdbc.update(
        "UPDATE kb_knowledge_entry SET stage='WIKI',lifecycle='ACTIVE',updated_at=clock_timestamp() WHERE knowledge_base_id=? AND entry_id=?",
        knowledgeBaseId,
        frozenEntry.id());
  }

  @Override
  public void graphPublished(Entry frozenEntry) {
    requirePublication();
    var current =
        find(frozenEntry.id())
            .orElseThrow(() -> new IllegalStateException("graph entry disappeared"));
    // Wiki promotion does not alter semantic inputs or prevent independent Graph publication.
    if (current.revision() != frozenEntry.revision()
        || !current.sources().equals(frozenEntry.sources())
        || !current.buildInputs().equals(frozenEntry.buildInputs()))
      throw new IllegalStateException("graph entry inputs changed");
    current.buildInputs().forEach(input -> verifyBuildInput(input, true));
    jdbc.update(
        "UPDATE kb_entry_pending_source SET graph_repaired_at=clock_timestamp() WHERE knowledge_base_id=? AND entry_id=? AND associated_at IS NOT NULL AND graph_repaired_at IS NULL",
        knowledgeBaseId,
        current.id());
  }

  @Override
  public void relatedSource(
      Entry frozenEntry, String versionId, List<String> evidenceIds, String literalImpact) {
    requirePublication();
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        knowledgeBaseId + ":" + frozenEntry.id());
    var current =
        find(frozenEntry.id())
            .orElseThrow(() -> new IllegalStateException("related entry disappeared"));
    if (!current.equals(frozenEntry))
      throw new IllegalStateException("related entry changed after model input");
    current.buildInputs().forEach(input -> verifyBuildInput(input, true));
    if (evidenceIds == null
        || evidenceIds.isEmpty()
        || literalImpact == null
        || literalImpact.isBlank())
      throw new IllegalArgumentException("related source proof is required");
    var refs = evidenceIds.stream().distinct().sorted().toList();
    var args = new ArrayList<Object>();
    args.add(versionId);
    args.addAll(refs);
    var proof =
        jdbc.query(
            "SELECT b.raw_content FROM evidence e JOIN raw_block b ON b.block_id=e.block_id JOIN kb_source_authority a ON a.version_id=e.version_id "
                + "JOIN document_version v ON v.version_id=e.version_id JOIN document d ON d.document_id=e.document_id JOIN document_metadata m ON m.version_id=e.version_id "
                + "WHERE e.version_id=? AND e.active AND e.evidence_id IN ("
                + String.join(",", java.util.Collections.nCopies(refs.size(), "?"))
                + ") FOR SHARE OF e,b,v,d,m",
            (rs, n) -> rs.getString(1),
            args.toArray());
    if (proof.size() != refs.size() || proof.stream().noneMatch(p -> p.contains(literalImpact)))
      throw new IllegalStateException("related source impact lacks current raw support");
    if (relatedCandidates(versionId, 24).stream().noneMatch(e -> e.id().equals(current.id())))
      throw new IllegalStateException("related source lacks the entry's complete business scope");
    jdbc.update(
        "INSERT INTO kb_entry_pending_source(knowledge_base_id,entry_id,source_version_id,evidence_ids,impact_text,affected_source_versions) VALUES(?,?,?,?::jsonb,?,?::jsonb) "
            + "ON CONFLICT(knowledge_base_id,entry_id,source_version_id) DO UPDATE SET evidence_ids=EXCLUDED.evidence_ids,impact_text=EXCLUDED.impact_text,affected_source_versions=EXCLUDED.affected_source_versions,detected_at=clock_timestamp(),associated_at=NULL,graph_repaired_at=NULL",
        knowledgeBaseId,
        current.id(),
        versionId,
        json(refs),
        literalImpact,
        json(current.sources().stream().map(Source::versionId).distinct().sorted().toList()));
    jdbc.update(
        "UPDATE kb_knowledge_entry SET lifecycle='STALE',updated_at=clock_timestamp() WHERE knowledge_base_id=? AND entry_id=?",
        knowledgeBaseId,
        current.id());
    jdbc.update(
        "UPDATE wiki SET lifecycle='STALE' WHERE wiki_id=? AND lifecycle='ACTIVE'", current.id());
  }

  private static void requirePublication() {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException(
          "entry mutation requires the compilation publication transaction");
  }

  private List<String> strings(String json) {
    try {
      return mapper.readValue(json, new TypeReference<List<String>>() {});
    } catch (Exception failure) {
      throw new IllegalStateException("entry source JSON is invalid", failure);
    }
  }

  private java.util.Map<String, Object> objectMap(String json) {
    try {
      return mapper.readValue(json, new TypeReference<java.util.Map<String, Object>>() {});
    } catch (Exception failure) {
      throw new IllegalStateException("entry scope JSON is invalid", failure);
    }
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception failure) {
      throw new IllegalStateException("entry JSON cannot be encoded", failure);
    }
  }
}
