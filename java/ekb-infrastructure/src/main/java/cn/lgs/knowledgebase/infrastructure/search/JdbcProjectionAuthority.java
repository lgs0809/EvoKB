package cn.lgs.knowledgebase.infrastructure.search;

import cn.lgs.knowledgebase.application.port.Ports.DerivedContext;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcAssetCatalogue;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reject an entire derived projection if any input is unknown, stale, deleted, or inaccessible. */
public final class JdbcProjectionAuthority
    implements MilvusKnowledgeIndexAdapter.ProjectionAuthority,
        cn.lgs.knowledgebase.application.port.Ports.SourceVersionAuthority {
  private final JdbcTemplate jdbc;
  private final JdbcAssetCatalogue assets;

  public JdbcProjectionAuthority(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.assets =
        new JdbcAssetCatalogue(
            jdbc,
            new TransactionTemplate(
                new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()))));
  }

  @Override
  public boolean legacyProjectionCurrent(String id) {
    Integer current =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM kb_asset_current WHERE asset_id=?", Integer.class, id);
    return current != null && current == 0;
  }

  @Override
  public boolean rawProjectionCurrent(String id, List<String> versions) {
    if (versions == null || versions.size() != 1) return false;
    String version = versions.get(0);
    Long pointers =
        jdbc.queryForObject(
            "SELECT count(*) FROM raw_publication WHERE version_id=?", Long.class, version);
    if (pointers == null || pointers == 0) return !id.matches(".*:e[0-9a-f]{24}$");
    return jdbc.queryForObject(
            "SELECT count(*) FROM raw_publication p JOIN raw_publication_member m USING(version_id) JOIN document_metadata a USING(version_id) JOIN kb_source_authority s USING(version_id) WHERE p.version_id=? AND m.physical_id=? AND p.acl_version=a.acl_version",
            Integer.class,
            version,
            id)
        == 1;
  }

  @Override
  public Optional<Map<String, List<String>>> rawVisibility(SearchFilters filters) {
    int clearance = filters == null ? 10 : filters.securityClearance();
    var scopes =
        jdbc.query(
            "SELECT DISTINCT p.producer_scope FROM raw_publication p JOIN kb_source_authority s USING(version_id) JOIN document_metadata m USING(version_id) WHERE s.security_level<=? AND p.acl_version=m.acl_version",
            (rs, n) -> rs.getString(1),
            clearance);
    var legacy =
        jdbc.query(
            "SELECT s.version_id FROM kb_source_authority s WHERE s.security_level<=? AND NOT EXISTS(SELECT 1 FROM raw_publication p WHERE p.version_id=s.version_id)",
            (rs, n) -> rs.getString(1),
            clearance);
    return Optional.of(Map.of("scopes", scopes, "legacyVersions", legacy));
  }

  @Override
  public Map<String, Object> prepareAsset(
      String id,
      String stage,
      String projection,
      List<String> evidenceIds,
      DerivedContext context) {
    return assets.stage(id, stage, projection, evidenceIds, context);
  }

  @Override
  public void publishAsset(String id, long revision) {
    assets.publish(id, revision);
  }

  @Override
  public Optional<List<Map<String, Object>>> currentAssetSources(
      String id, long revision, SearchFilters filters) {
    return assets.currentSources(id, revision, filters);
  }

  @Override
  public Map<String, Object> registerAsset(
      String id,
      String stage,
      String projection,
      List<String> evidenceIds,
      DerivedContext context) {
    return assets.register(id, stage, projection, evidenceIds, context);
  }

  @Override
  public Optional<List<Map<String, Object>>> assetSources(
      String id, long revision, SearchFilters filters) {
    return assets.sources(id, revision, filters);
  }

  @Override
  public int securityLevel(List<String> versionIds) {
    List<String> ids = identities(versionIds);
    var rows =
        jdbc.query(
            "SELECT m.security_level FROM document_version v JOIN document_metadata m ON m.version_id=v.version_id "
                + "JOIN document d ON d.document_id=v.document_id WHERE d.status='ACTIVE' AND v.lifecycle IN ('PENDING','ACTIVE') AND v.version_id IN ("
                + placeholders(ids)
                + ")",
            (rs, n) -> rs.getInt(1),
            ids.toArray());
    if (rows.size() != ids.size())
      throw new IllegalStateException("projection has missing or invalid sources");
    return rows.stream().mapToInt(Integer::intValue).max().orElseThrow();
  }

  @Override
  public boolean accessible(List<String> versionIds, SearchFilters filters) {
    if (versionIds == null || versionIds.isEmpty()) return false;
    List<String> ids = identities(versionIds);
    int clearance = filters == null ? 10 : filters.securityClearance();
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM kb_source_authority WHERE security_level <= ? AND version_id IN ("
                + placeholders(ids)
                + ")",
            Integer.class,
            java.util.stream.Stream.concat(java.util.stream.Stream.of(clearance), ids.stream())
                .toArray());
    return count != null && count == ids.size();
  }

  private static List<String> identities(List<String> ids) {
    if (ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id.isBlank()))
      throw new IllegalArgumentException("authoritative source versions are required");
    return ids.stream().distinct().toList();
  }

  private static String placeholders(List<String> ids) {
    return String.join(",", Collections.nCopies(ids.size(), "?"));
  }
}
