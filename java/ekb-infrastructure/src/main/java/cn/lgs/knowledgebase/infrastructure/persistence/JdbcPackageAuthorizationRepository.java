package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.port.Ports.PackageAuthorization;
import cn.lgs.knowledgebase.application.port.Ports.PackageAuthorizationRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcPackageAuthorizationRepository implements PackageAuthorizationRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcPackageAuthorizationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public void save(PackageAuthorization authorization) {
    try {
      jdbc.update(
          "INSERT INTO source_package_access(package_id,user_id,object_key,evidence_ids,expires_at) VALUES(?,?,?,?::jsonb,?)",
          authorization.packageId(),
          authorization.userId(),
          authorization.objectKey(),
          mapper.writeValueAsString(authorization.evidenceIds()),
          java.sql.Timestamp.from(authorization.expiresAt()));
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException("package authorization serialization failed", invalid);
    }
  }

  public Optional<PackageAuthorization> find(String packageId, String userId) {
    return select("package_id", packageId, userId);
  }

  public Optional<PackageAuthorization> findByObjectKey(String objectKey, String userId) {
    return select("object_key", objectKey, userId);
  }

  private Optional<PackageAuthorization> select(String column, String value, String userId) {
    return jdbc
        .query(
            "SELECT package_id,user_id,object_key,evidence_ids,expires_at FROM source_package_access WHERE "
                + column
                + "=? AND user_id=? AND deleted_at IS NULL AND expires_at>CURRENT_TIMESTAMP",
            (rs, n) -> {
              try {
                return new PackageAuthorization(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    mapper.readValue(rs.getString(4), new TypeReference<List<String>>() {}),
                    rs.getTimestamp(5).toInstant());
              } catch (java.io.IOException invalid) {
                throw new IllegalStateException("package authorization is invalid", invalid);
              }
            },
            value,
            userId)
        .stream()
        .findFirst();
  }

  public void markDeleted(String objectKey) {
    jdbc.update(
        "UPDATE source_package_access SET deleted_at=CURRENT_TIMESTAMP WHERE object_key=? AND deleted_at IS NULL AND expires_at>CURRENT_TIMESTAMP",
        objectKey);
  }
}
