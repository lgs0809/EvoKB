package cn.lgs.knowledgebase.infrastructure.raw;

import cn.lgs.knowledgebase.application.raw.RawVectorCache;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Content-addressed calculation cache. No plaintext, source ACL or publication state is cached. */
public final class JdbcRawVectorCache implements RawVectorCache {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcRawVectorCache(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc);
    this.mapper = java.util.Objects.requireNonNull(mapper);
  }

  @Override
  public Optional<List<Float>> find(String key, String profile, String input, int dimensions) {
    List<String> found =
        jdbc.query(
            "SELECT vector_json FROM raw_vector_cache WHERE cache_key=? AND profile_hash=? AND input_hash=? AND dimensions=?",
            (rs, row) -> rs.getString(1),
            key,
            profile,
            input,
            dimensions);
    if (found.isEmpty()) return Optional.empty();
    try {
      List<Float> vector = mapper.readValue(found.get(0), new TypeReference<List<Float>>() {});
      validate(vector, dimensions);
      return Optional.of(List.copyOf(vector));
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Malformed cached raw vector", error);
    }
  }

  @Override
  public void put(String key, String profile, String input, int dimensions, List<Float> vector) {
    validate(vector, dimensions);
    try {
      jdbc.update(
          "INSERT INTO raw_vector_cache(cache_key,profile_hash,input_hash,dimensions,vector_json) VALUES(?,?,?,?,?) ON CONFLICT(cache_key) DO NOTHING",
          key,
          profile,
          input,
          dimensions,
          mapper.writeValueAsString(vector));
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot serialize raw vector cache", error);
    }
  }

  private static void validate(List<Float> vector, int dimensions) {
    if (dimensions <= 0
        || vector == null
        || vector.size() != dimensions
        || vector.stream().anyMatch(value -> value == null || !Float.isFinite(value)))
      throw new IllegalStateException("Invalid cached raw vector dimensions or values");
  }
}
