package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.port.Ports.DerivedContext;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL owns entry identity, source membership and immutable input provenance. */
public final class JdbcAssetCatalogue {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper mapper = new ObjectMapper();

  public JdbcAssetCatalogue(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transactions = Objects.requireNonNull(transactions);
  }

  public Map<String, Object> register(
      String id,
      String stage,
      String projection,
      List<String> evidenceIds,
      DerivedContext context) {
    var attributes = stage(id, stage, projection, evidenceIds, context);
    publish(id, ((Number) attributes.get("asset_version")).longValue());
    return attributes;
  }

  public Map<String, Object> stage(
      String id,
      String stage,
      String projection,
      List<String> evidenceIds,
      DerivedContext context) {
    if (id == null
        || id.isBlank()
        || !Set.of("SUMMARY", "CONCEPT", "WIKI").contains(stage)
        || projection == null
        || projection.isBlank())
      throw new IllegalArgumentException("entry projection identity is required");
    var versions = sorted(context.versionIds());
    var evidence = sorted(evidenceIds);
    if (versions.isEmpty() || evidence.isEmpty())
      throw new IllegalArgumentException(
          "entry inputs and supported source membership are required");
    return transactions.execute(
        status -> {
          // Serializes only this business entry; concurrent identical publications reuse its
          // revision.
          jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", id);
          var inputs =
              jdbc.query(
                  """
          SELECT a.version_id,a.document_id,m.acl_version FROM kb_source_authority a
          JOIN document_version v ON v.version_id=a.version_id
          JOIN document d ON d.document_id=a.document_id
          JOIN document_metadata m ON m.version_id=a.version_id
          WHERE a.version_id IN (
          """
                      + marks(versions.size())
                      + ") ORDER BY a.version_id FOR SHARE OF v,d,m",
                  (rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getLong(3)),
                  versions.toArray());
          if (inputs.size() != versions.size())
            throw new IllegalStateException("entry has stale or unknown build inputs");
          var proofs =
              jdbc.query(
                  "SELECT evidence_id,document_id,version_id FROM evidence WHERE active=TRUE AND evidence_id IN ("
                      + marks(evidence.size())
                      + ") ORDER BY evidence_id FOR SHARE",
                  (rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3)),
                  evidence.toArray());
          if (proofs.size() != evidence.size()
              || proofs.stream().anyMatch(p -> !versions.contains(p.get(2))))
            throw new IllegalArgumentException(
                "entry catalogue proof is absent from its actual inputs");
          var docs = sorted(context.documentIds());
          if (!docs.equals(
              inputs.stream().map(p -> p.get(1).toString()).distinct().sorted().toList()))
            throw new IllegalArgumentException(
                "entry source document identities do not match versions");
          String hash = fingerprint(List.of(stage, projection, inputs, proofs));
          var prior =
              jdbc.query(
                  "SELECT asset_version FROM kb_derived_asset WHERE asset_id=? AND projection_hash=?",
                  (rs, n) -> rs.getLong(1),
                  id,
                  hash);
          if (!prior.isEmpty()) return attributes(id, prior.get(0));
          Long revision =
              jdbc.queryForObject(
                  "SELECT COALESCE(MAX(asset_version),0)+1 FROM kb_derived_asset WHERE asset_id=?",
                  Long.class,
                  id);
          jdbc.update(
              "INSERT INTO kb_derived_asset(asset_id,asset_version,stage,projection_hash,publication_state) VALUES(?,?,?,?,'STAGED')",
              id,
              revision,
              stage,
              hash);
          for (var input : inputs)
            jdbc.update(
                "INSERT INTO kb_asset_dependency(asset_id,asset_version,source_version_id,acl_version) VALUES(?,?,?,?)",
                id,
                revision,
                input.get(0),
                input.get(2));
          var membership = new TreeMap<String, List<String>>();
          for (var proof : proofs)
            membership.computeIfAbsent(proof.get(2), v -> new ArrayList<>()).add(proof.get(0));
          for (var input : inputs) {
            var refs = membership.get(input.get(0).toString());
            if (refs != null)
              jdbc.update(
                  "INSERT INTO kb_asset_source(asset_id,asset_version,document_id,source_version_id,evidence_ids) VALUES(?,?,?,?,?::jsonb)",
                  id,
                  revision,
                  input.get(1),
                  input.get(0),
                  json(refs));
          }
          return attributes(id, revision);
        });
  }

  /** Called within the compilation commit after object/index staging is acknowledged. */
  public void publish(String id, long revision) {
    transactions.executeWithoutResult(
        ignored -> {
          jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", id);
          var dependencies =
              jdbc.query(
                  "SELECT d.source_version_id,d.acl_version,m.acl_version FROM kb_asset_dependency d "
                      + "JOIN kb_source_authority a ON a.version_id=d.source_version_id "
                      + "JOIN document_version v ON v.version_id=a.version_id JOIN document doc ON doc.document_id=a.document_id "
                      + "JOIN document_metadata m ON m.version_id=a.version_id WHERE d.asset_id=? AND d.asset_version=? "
                      + "ORDER BY d.source_version_id FOR SHARE OF v,doc,m",
                  (rs, n) -> rs.getLong(2) == rs.getLong(3),
                  id,
                  revision);
          Integer total =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM kb_asset_dependency WHERE asset_id=? AND asset_version=?",
                  Integer.class,
                  id,
                  revision);
          if (total == null
              || total == 0
              || dependencies.size() != total
              || dependencies.contains(false))
            throw new IllegalStateException("staged projection sources changed before publication");
          var current =
              jdbc.query(
                  "SELECT asset_version FROM kb_asset_current WHERE asset_id=? FOR UPDATE",
                  (rs, n) -> rs.getLong(1),
                  id);
          if (!current.isEmpty() && current.get(0) > revision)
            throw new IllegalStateException(
                "an older projection cannot replace the current revision");
          if (jdbc.update(
                  "UPDATE kb_derived_asset SET publication_state='PUBLISHED' WHERE asset_id=? AND asset_version=?",
                  id,
                  revision)
              != 1) throw new IllegalStateException("staged projection is absent");
          jdbc.update(
              "INSERT INTO kb_asset_current(asset_id,asset_version) VALUES(?,?) ON CONFLICT(asset_id) "
                  + "DO UPDATE SET asset_version=EXCLUDED.asset_version,published_at=clock_timestamp()",
              id,
              revision);
        });
  }

  public Optional<List<Map<String, Object>>> currentSources(
      String id, long revision, SearchFilters filters) {
    Integer current =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM kb_asset_current WHERE asset_id=? AND asset_version=?",
            Integer.class,
            id,
            revision);
    return current != null && current == 1 ? sources(id, revision, filters) : Optional.empty();
  }

  /** Empty for unknown or revoked revisions. Never read membership from a search projection. */
  public Optional<List<Map<String, Object>>> sources(
      String id, long revision, SearchFilters filters) {
    int clearance = filters == null ? 10 : filters.securityClearance();
    Integer valid =
        jdbc.queryForObject(
            """
        SELECT COUNT(*) FROM kb_derived_asset r WHERE r.asset_id=? AND r.asset_version=? AND r.publication_state='PUBLISHED'
          AND NOT EXISTS(SELECT 1 FROM kb_knowledge_entry e WHERE e.entry_id=r.asset_id AND e.lifecycle<>'ACTIVE')
          AND EXISTS(SELECT 1 FROM kb_asset_dependency d WHERE d.asset_id=r.asset_id AND d.asset_version=r.asset_version)
          AND NOT EXISTS(SELECT 1 FROM kb_asset_dependency d
            LEFT JOIN kb_source_authority s ON s.version_id=d.source_version_id
            WHERE d.asset_id=r.asset_id AND d.asset_version=r.asset_version
              AND (s.version_id IS NULL OR s.security_level>? OR s.acl_version<>d.acl_version))
        """,
            Integer.class,
            id,
            revision,
            clearance);
    if (valid == null || valid != 1) return Optional.empty();
    return Optional.of(
        jdbc.query(
            "SELECT document_id,source_version_id,evidence_ids::text FROM kb_asset_source WHERE asset_id=? AND asset_version=? ORDER BY source_version_id",
            (rs, n) ->
                Map.<String, Object>of(
                    "document_id",
                    rs.getString(1),
                    "source_version",
                    rs.getString(2),
                    "evidence_ids",
                    strings(rs.getString(3))),
            id,
            revision));
  }

  private static Map<String, Object> attributes(String id, long revision) {
    return Map.of("asset_id", id, "asset_version", revision);
  }

  private static List<String> sorted(List<String> values) {
    if (values == null || values.stream().anyMatch(v -> v == null || v.isBlank()))
      throw new IllegalArgumentException("source identities are required");
    return values.stream().distinct().sorted().toList();
  }

  private static String marks(int count) {
    return String.join(",", Collections.nCopies(count, "?"));
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception error) {
      throw new IllegalStateException("invalid entry provenance", error);
    }
  }

  private List<String> strings(String value) {
    try {
      return mapper.readValue(value, new TypeReference<List<String>>() {});
    } catch (Exception error) {
      throw new IllegalStateException("invalid entry catalogue", error);
    }
  }

  private String fingerprint(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(json(value).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }
}
