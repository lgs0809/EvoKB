package cn.lgs.knowledgebase.infrastructure.model;

import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.EffectiveConfiguration;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.ProfileConfiguration;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.ProfileUpdateCommand;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.Store;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.UpdateCommand;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists named model configuration profiles while keeping environment values as bootstrap
 * defaults.
 */
public final class JdbcModelConfigurationStore implements Store {
  private static final int LEGACY_SINGLETON_ID = 1;
  private static final String PROFILE_COLUMNS =
      "profile_id,name,active,chat_base_url,chat_api_key,small_model,large_model,chat_timeout_seconds,"
          + "embedding_base_url,embedding_api_key,embedding_model,embedding_dimensions,embedding_batch_size,embedding_timeout_seconds,"
          + "rerank_base_url,rerank_api_key,rerank_model,rerank_timeout_seconds,created_at,updated_at";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final EffectiveConfiguration defaults;

  public JdbcModelConfigurationStore(
      JdbcTemplate jdbc, TransactionTemplate tx, EffectiveConfiguration defaults) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tx = Objects.requireNonNull(tx, "transaction template");
    this.defaults = Objects.requireNonNull(defaults, "model configuration defaults");
    ensureSchema();
    migrateLegacyConfiguration();
    repairBootstrapProfile();
  }

  @Override
  public EffectiveConfiguration current() {
    List<ProfileConfiguration> rows =
        jdbc.query(
            "SELECT "
                + PROFILE_COLUMNS
                + " FROM model_configuration_profile WHERE active=1 LIMIT 1",
            (rs, rowNum) -> scanProfile(rs));
    return rows.isEmpty() ? defaults : rows.get(0).effective();
  }

  @Override
  public EffectiveConfiguration update(UpdateCommand command) {
    ProfileConfiguration active = activeProfile();
    EffectiveConfiguration merged = merge(active.effective(), command, Instant.now());
    updateProfile(
        active.profileId(),
        new ProfileUpdateCommand(
            active.name(),
            merged.chatBaseUrl(),
            merged.chatApiKey(),
            merged.smallModel(),
            merged.largeModel(),
            merged.chatTimeoutSeconds(),
            merged.embeddingBaseUrl(),
            merged.embeddingApiKey(),
            merged.embeddingModel(),
            merged.embeddingDimensions(),
            merged.embeddingBatchSize(),
            merged.embeddingTimeoutSeconds(),
            merged.rerankBaseUrl(),
            merged.rerankApiKey(),
            merged.rerankModel(),
            merged.rerankTimeoutSeconds()));
    return current();
  }

  @Override
  public List<ProfileConfiguration> profiles() {
    return jdbc.query(
        "SELECT "
            + PROFILE_COLUMNS
            + " FROM model_configuration_profile ORDER BY active DESC,updated_at DESC,created_at ASC",
        (rs, rowNum) -> scanProfile(rs));
  }

  @Override
  public ProfileConfiguration create(String name) {
    EffectiveConfiguration source = current();
    Instant now = Instant.now();
    String id = UUID.randomUUID().toString();
    insertProfile(id, name, false, source, now, now);
    return profile(id);
  }

  @Override
  public ProfileConfiguration updateProfile(String profileId, ProfileUpdateCommand command) {
    ProfileConfiguration current = profile(profileId);
    Instant now = Instant.now();
    ProfileConfiguration next = normalize(current, command, now);
    jdbc.update(
        "UPDATE model_configuration_profile SET name=?,chat_base_url=?,chat_api_key=?,small_model=?,large_model=?,chat_timeout_seconds=?,"
            + "embedding_base_url=?,embedding_api_key=?,embedding_model=?,embedding_dimensions=?,embedding_batch_size=?,embedding_timeout_seconds=?,"
            + "rerank_base_url=?,rerank_api_key=?,rerank_model=?,rerank_timeout_seconds=?,updated_at=? WHERE profile_id=?",
        next.name(),
        next.chatBaseUrl(),
        nullable(next.chatApiKey()),
        next.smallModel(),
        next.largeModel(),
        next.chatTimeoutSeconds(),
        next.embeddingBaseUrl(),
        nullable(next.embeddingApiKey()),
        next.embeddingModel(),
        next.embeddingDimensions(),
        next.embeddingBatchSize(),
        next.embeddingTimeoutSeconds(),
        next.rerankBaseUrl(),
        nullable(next.rerankApiKey()),
        next.rerankModel(),
        next.rerankTimeoutSeconds(),
        Timestamp.from(now),
        profileId);
    return profile(profileId);
  }

  @Override
  public ProfileConfiguration copy(String profileId) {
    ProfileConfiguration source = profile(profileId);
    String id = UUID.randomUUID().toString();
    Instant now = Instant.now();
    insertProfile(id, source.name() + " 副本", false, source.effective(), now, now);
    return profile(id);
  }

  @Override
  public ProfileConfiguration activate(String profileId) {
    return tx.execute(
        status -> {
          ProfileConfiguration next = profile(profileId);
          validateActivatable(next);
          Instant now = Instant.now();
          jdbc.update("UPDATE model_configuration_profile SET active=0 WHERE active=1");
          int rows =
              jdbc.update(
                  "UPDATE model_configuration_profile SET active=1,updated_at=? WHERE profile_id=?",
                  Timestamp.from(now),
                  profileId);
          if (rows != 1) throw new IllegalStateException("model configuration profile not found");
          return profile(profileId);
        });
  }

  @Override
  public void delete(String profileId) {
    ProfileConfiguration profile = profile(profileId);
    if (profile.active())
      throw new IllegalStateException("active model configuration cannot be deleted");
    int rows = jdbc.update("DELETE FROM model_configuration_profile WHERE profile_id=?", profileId);
    if (rows != 1) throw new IllegalStateException("model configuration profile not found");
  }

  private ProfileConfiguration activeProfile() {
    return profiles().stream()
        .filter(ProfileConfiguration::active)
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("active model configuration profile is missing"));
  }

  private ProfileConfiguration profile(String profileId) {
    List<ProfileConfiguration> rows =
        jdbc.query(
            "SELECT " + PROFILE_COLUMNS + " FROM model_configuration_profile WHERE profile_id=?",
            (rs, rowNum) -> scanProfile(rs),
            profileId);
    if (rows.isEmpty()) throw new IllegalStateException("model configuration profile not found");
    return rows.get(0);
  }

  private ProfileConfiguration scanProfile(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ProfileConfiguration(
        rs.getString("profile_id"),
        rs.getString("name"),
        rs.getBoolean("active"),
        rs.getString("chat_base_url"),
        text(rs.getString("chat_api_key")),
        rs.getString("small_model"),
        rs.getString("large_model"),
        rs.getInt("chat_timeout_seconds"),
        rs.getString("embedding_base_url"),
        text(rs.getString("embedding_api_key")),
        rs.getString("embedding_model"),
        rs.getInt("embedding_dimensions"),
        rs.getInt("embedding_batch_size"),
        rs.getInt("embedding_timeout_seconds"),
        rs.getString("rerank_base_url"),
        text(rs.getString("rerank_api_key")),
        rs.getString("rerank_model"),
        rs.getInt("rerank_timeout_seconds"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private void insertProfile(
      String id,
      String name,
      boolean active,
      EffectiveConfiguration value,
      Instant createdAt,
      Instant updatedAt) {
    jdbc.update(
        "INSERT INTO model_configuration_profile ("
            + PROFILE_COLUMNS
            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        name,
        active,
        value.chatBaseUrl(),
        nullable(value.chatApiKey()),
        value.smallModel(),
        value.largeModel(),
        value.chatTimeoutSeconds(),
        value.embeddingBaseUrl(),
        nullable(value.embeddingApiKey()),
        value.embeddingModel(),
        value.embeddingDimensions(),
        value.embeddingBatchSize(),
        value.embeddingTimeoutSeconds(),
        value.rerankBaseUrl(),
        nullable(value.rerankApiKey()),
        value.rerankModel(),
        value.rerankTimeoutSeconds(),
        Timestamp.from(createdAt),
        Timestamp.from(updatedAt));
  }

  private ProfileConfiguration normalize(
      ProfileConfiguration current, ProfileUpdateCommand command, Instant now) {
    return new ProfileConfiguration(
        current.profileId(),
        requiredName(command.name()),
        current.active(),
        optionalUrl(command.chatBaseUrl(), "chat base URL"),
        secret(current.chatApiKey(), command.chatApiKey(), false),
        text(command.smallModel()).trim(),
        text(command.largeModel()).trim(),
        positive(command.chatTimeoutSeconds(), "chat timeout seconds"),
        optionalUrl(command.embeddingBaseUrl(), "embedding base URL"),
        secret(current.embeddingApiKey(), command.embeddingApiKey(), false),
        text(command.embeddingModel()).trim(),
        positive(command.embeddingDimensions(), "embedding dimensions"),
        positive(command.embeddingBatchSize(), "embedding batch size"),
        positive(command.embeddingTimeoutSeconds(), "embedding timeout seconds"),
        optionalUrl(command.rerankBaseUrl(), "rerank base URL"),
        secret(current.rerankApiKey(), command.rerankApiKey(), false),
        text(command.rerankModel()).trim(),
        positive(command.rerankTimeoutSeconds(), "rerank timeout seconds"),
        current.createdAt(),
        now);
  }

  private void validateActivatable(ProfileConfiguration value) {
    requiredUrl(value.chatBaseUrl(), "chat base URL");
    required(value.smallModel(), "small model");
    required(value.largeModel(), "large model");
    requiredUrl(value.embeddingBaseUrl(), "embedding base URL");
    required(value.embeddingModel(), "embedding model");
    requiredUrl(value.rerankBaseUrl(), "rerank base URL");
    required(value.rerankModel(), "rerank model");
  }

  private EffectiveConfiguration merge(
      EffectiveConfiguration current, UpdateCommand command, Instant updatedAt) {
    return new EffectiveConfiguration(
        url(command.chatBaseUrl(), current.chatBaseUrl(), "chat base URL"),
        secret(current.chatApiKey(), command.chatApiKey(), command.clearChatApiKey()),
        required(command.smallModel(), current.smallModel(), "small model"),
        required(command.largeModel(), current.largeModel(), "large model"),
        positive(
            command.chatTimeoutSeconds(), current.chatTimeoutSeconds(), "chat timeout seconds"),
        url(command.embeddingBaseUrl(), current.embeddingBaseUrl(), "embedding base URL"),
        secret(
            current.embeddingApiKey(), command.embeddingApiKey(), command.clearEmbeddingApiKey()),
        required(command.embeddingModel(), current.embeddingModel(), "embedding model"),
        positive(
            command.embeddingDimensions(), current.embeddingDimensions(), "embedding dimensions"),
        positive(
            command.embeddingBatchSize(), current.embeddingBatchSize(), "embedding batch size"),
        positive(
            command.embeddingTimeoutSeconds(),
            current.embeddingTimeoutSeconds(),
            "embedding timeout seconds"),
        url(command.rerankBaseUrl(), current.rerankBaseUrl(), "rerank base URL"),
        secret(current.rerankApiKey(), command.rerankApiKey(), command.clearRerankApiKey()),
        required(command.rerankModel(), current.rerankModel(), "rerank model"),
        positive(
            command.rerankTimeoutSeconds(),
            current.rerankTimeoutSeconds(),
            "rerank timeout seconds"),
        updatedAt);
  }

  private void ensureSchema() {
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS model_configuration ("
            + "configuration_id TINYINT UNSIGNED PRIMARY KEY,"
            + "chat_base_url VARCHAR(2048) NOT NULL,chat_api_key TEXT NULL,small_model VARCHAR(255) NOT NULL,large_model VARCHAR(255) NOT NULL,chat_timeout_seconds INT UNSIGNED NOT NULL,"
            + "embedding_base_url VARCHAR(2048) NOT NULL,embedding_api_key TEXT NULL,embedding_model VARCHAR(255) NOT NULL,"
            + "embedding_dimensions INT UNSIGNED NOT NULL,embedding_batch_size INT UNSIGNED NOT NULL,embedding_timeout_seconds INT UNSIGNED NOT NULL,"
            + "rerank_base_url VARCHAR(2048) NOT NULL,rerank_api_key TEXT NULL,rerank_model VARCHAR(255) NOT NULL,rerank_timeout_seconds INT UNSIGNED NOT NULL,"
            + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)) ENGINE=InnoDB");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS model_configuration_profile ("
            + "profile_id VARCHAR(36) PRIMARY KEY,name VARCHAR(128) NOT NULL,active BOOLEAN NOT NULL DEFAULT FALSE,"
            + "chat_base_url VARCHAR(2048) NOT NULL,chat_api_key TEXT NULL,small_model VARCHAR(255) NOT NULL,large_model VARCHAR(255) NOT NULL,chat_timeout_seconds INT UNSIGNED NOT NULL,"
            + "embedding_base_url VARCHAR(2048) NOT NULL,embedding_api_key TEXT NULL,embedding_model VARCHAR(255) NOT NULL,"
            + "embedding_dimensions INT UNSIGNED NOT NULL,embedding_batch_size INT UNSIGNED NOT NULL,embedding_timeout_seconds INT UNSIGNED NOT NULL,"
            + "rerank_base_url VARCHAR(2048) NOT NULL,rerank_api_key TEXT NULL,rerank_model VARCHAR(255) NOT NULL,rerank_timeout_seconds INT UNSIGNED NOT NULL,"
            + "created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),"
            + "INDEX idx_model_configuration_profile_active(active)) ENGINE=InnoDB");
  }

  private void migrateLegacyConfiguration() {
    Integer count =
        jdbc.queryForObject("SELECT COUNT(*) FROM model_configuration_profile", Integer.class);
    if (count != null && count > 0) return;
    EffectiveConfiguration seed = legacyCurrent();
    Instant candidate = seed.updatedAt();
    Instant now =
        candidate == null || candidate.isBefore(Instant.parse("2000-01-01T00:00:00Z"))
            ? Instant.now()
            : candidate;
    insertProfile(UUID.randomUUID().toString(), "默认配置", true, seed, now, now);
  }

  private void repairBootstrapProfile() {
    ProfileConfiguration active = activeProfile();
    if (!"默认配置".equals(active.name())) return;
    boolean incomplete =
        active.chatBaseUrl().isBlank()
            || active.embeddingBaseUrl().isBlank()
            || active.embeddingModel().isBlank()
            || active.rerankBaseUrl().isBlank()
            || active.rerankModel().isBlank();
    if (!incomplete) return;
    updateProfile(
        active.profileId(),
        new ProfileUpdateCommand(
            active.name(),
            prefer(active.chatBaseUrl(), defaults.chatBaseUrl()),
            prefer(active.chatApiKey(), defaults.chatApiKey()),
            prefer(active.smallModel(), defaults.smallModel()),
            prefer(active.largeModel(), defaults.largeModel()),
            active.chatTimeoutSeconds(),
            prefer(active.embeddingBaseUrl(), defaults.embeddingBaseUrl()),
            prefer(active.embeddingApiKey(), defaults.embeddingApiKey()),
            prefer(active.embeddingModel(), defaults.embeddingModel()),
            active.embeddingDimensions(),
            active.embeddingBatchSize(),
            active.embeddingTimeoutSeconds(),
            prefer(active.rerankBaseUrl(), defaults.rerankBaseUrl()),
            prefer(active.rerankApiKey(), defaults.rerankApiKey()),
            prefer(active.rerankModel(), defaults.rerankModel()),
            active.rerankTimeoutSeconds()));
  }

  private EffectiveConfiguration legacyCurrent() {
    List<EffectiveConfiguration> rows =
        jdbc.query(
            "SELECT chat_base_url,chat_api_key,small_model,large_model,chat_timeout_seconds,"
                + "embedding_base_url,embedding_api_key,embedding_model,embedding_dimensions,embedding_batch_size,embedding_timeout_seconds,"
                + "rerank_base_url,rerank_api_key,rerank_model,rerank_timeout_seconds,updated_at FROM model_configuration WHERE configuration_id=?",
            (rs, rowNum) ->
                new EffectiveConfiguration(
                    rs.getString("chat_base_url"),
                    text(rs.getString("chat_api_key")),
                    rs.getString("small_model"),
                    rs.getString("large_model"),
                    rs.getInt("chat_timeout_seconds"),
                    rs.getString("embedding_base_url"),
                    text(rs.getString("embedding_api_key")),
                    rs.getString("embedding_model"),
                    rs.getInt("embedding_dimensions"),
                    rs.getInt("embedding_batch_size"),
                    rs.getInt("embedding_timeout_seconds"),
                    rs.getString("rerank_base_url"),
                    text(rs.getString("rerank_api_key")),
                    rs.getString("rerank_model"),
                    rs.getInt("rerank_timeout_seconds"),
                    rs.getTimestamp("updated_at").toInstant()),
            LEGACY_SINGLETON_ID);
    return rows.isEmpty() ? defaults : rows.get(0);
  }

  private static String requiredName(String value) {
    String name = required(value, "profile name");
    if (name.length() > 128) throw new IllegalArgumentException("profile name is too long");
    return name;
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(field + " is required");
    return value.trim();
  }

  private static String required(String requested, String current, String field) {
    return required(requested == null ? current : requested, field);
  }

  private static String requiredUrl(String value, String field) {
    String url = required(value, field);
    if (!(url.startsWith("http://") || url.startsWith("https://"))) {
      throw new IllegalArgumentException(field + " must start with http:// or https://");
    }
    return trimSlash(url);
  }

  private static String optionalUrl(String value, String field) {
    String url = text(value).trim();
    if (url.isEmpty()) return "";
    if (!(url.startsWith("http://") || url.startsWith("https://"))) {
      throw new IllegalArgumentException(field + " must start with http:// or https://");
    }
    return trimSlash(url);
  }

  private static String url(String requested, String current, String field) {
    return requiredUrl(requested == null ? current : requested, field);
  }

  private static String secret(String current, String requested, Boolean clear) {
    if (Boolean.TRUE.equals(clear)) return "";
    if (requested == null || requested.isBlank()) return text(current);
    return requested.trim();
  }

  private static int positive(Integer value, String field) {
    if (value == null || value <= 0)
      throw new IllegalArgumentException(field + " must be positive");
    return value;
  }

  private static int positive(Integer requested, int current, String field) {
    int value = requested == null ? current : requested;
    if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
    return value;
  }

  private static String trimSlash(String value) {
    String next = value;
    while (next.endsWith("/")) next = next.substring(0, next.length() - 1);
    return next;
  }

  private static String nullable(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String prefer(String current, String fallback) {
    return current == null || current.isBlank() ? text(fallback) : current;
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }
}
