package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.port.Ports.RuntimeConfigRepository;
import cn.lgs.knowledgebase.application.port.Ports.SessionRepository;
import cn.lgs.knowledgebase.application.port.Ports.UserRepository;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.AccessPolicy;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.Role;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.Session;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.User;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.UserStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcIdentityRepository
    implements UserRepository, SessionRepository, RuntimeConfigRepository {
  private static final String USER_COLUMNS =
      "user_id,username,display_name,role,status,last_login_at,created_at,updated_at";
  private static final String UPLOAD_SETTING = "user_upload_enabled";
  private final JdbcTemplate jdbc;

  public JdbcIdentityRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public long count() {
    return scalar("SELECT COUNT(*) FROM enterprise_user");
  }

  @Override
  public long countLocked() {
    return scalar("SELECT COUNT(*) FROM enterprise_user FOR UPDATE");
  }

  @Override
  public long countActiveAdmins() {
    return scalar("SELECT COUNT(*) FROM enterprise_user WHERE role='ADMIN' AND status='ACTIVE'");
  }

  @Override
  public long countActiveAdminsLocked() {
    return scalar(
        "SELECT COUNT(*) FROM enterprise_user WHERE role='ADMIN' AND status='ACTIVE' FOR UPDATE");
  }

  @Override
  public Optional<User> findById(String id) {
    return jdbc
        .query("SELECT " + USER_COLUMNS + " FROM enterprise_user WHERE user_id=?", userMapper(), id)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<User> findByIdLocked(String id) {
    return jdbc
        .query(
            "SELECT " + USER_COLUMNS + " FROM enterprise_user WHERE user_id=? FOR UPDATE",
            userMapper(),
            id)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<User> findByUsername(String username) {
    return jdbc
        .query(
            "SELECT " + USER_COLUMNS + " FROM enterprise_user WHERE username=? LIMIT 1",
            userMapper(),
            username)
        .stream()
        .findFirst();
  }

  @Override
  public List<User> list(int offset, int limit) {
    return jdbc.query(
        "SELECT "
            + USER_COLUMNS
            + " FROM enterprise_user ORDER BY created_at,user_id LIMIT ? OFFSET ?",
        userMapper(),
        Math.max(1, limit),
        Math.max(0, offset));
  }

  @Override
  public void save(User user, String passwordHash) {
    try {
      jdbc.update(
          "INSERT INTO enterprise_user(user_id,username,display_name,password_hash,role,status,last_login_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
          user.id(),
          user.username(),
          user.displayName(),
          passwordHash,
          user.role().name(),
          user.status().name(),
          timestamp(user.lastLoginAt()),
          timestamp(user.createdAt()),
          timestamp(user.updatedAt()));
    } catch (DuplicateKeyException e) {
      throw new IllegalStateException("username already exists", e);
    }
  }

  @Override
  public void update(User user) {
    int rows =
        jdbc.update(
            "UPDATE enterprise_user SET display_name=?,role=?,status=?,last_login_at=?,updated_at=? WHERE user_id=?",
            user.displayName(),
            user.role().name(),
            user.status().name(),
            timestamp(user.lastLoginAt()),
            timestamp(user.updatedAt()),
            user.id());
    if (rows == 0) throw new IllegalStateException("user not found: " + user.id());
  }

  @Override
  public void updatePasswordHash(String userId, String passwordHash) {
    if (jdbc.update(
            "UPDATE enterprise_user SET password_hash=? WHERE user_id=?", passwordHash, userId)
        == 0) {
      throw new IllegalStateException("user not found: " + userId);
    }
  }

  @Override
  public Optional<String> passwordHash(String userId) {
    return jdbc
        .query(
            "SELECT password_hash FROM enterprise_user WHERE user_id=?",
            (rs, rowNum) -> rs.getString(1),
            userId)
        .stream()
        .findFirst();
  }

  @Override
  public void save(Session session) {
    jdbc.update(
        "INSERT INTO enterprise_user_session(session_hash,user_id,expires_at,last_seen_at,created_at) VALUES(?,?,?,?,?)",
        session.tokenHash(),
        session.userId(),
        timestamp(session.expiresAt()),
        timestamp(session.createdAt()),
        timestamp(session.createdAt()));
  }

  @Override
  public Optional<Session> findByTokenHash(String tokenHash) {
    return jdbc
        .query(
            "SELECT session_hash,user_id,expires_at,created_at FROM enterprise_user_session WHERE session_hash=?",
            (rs, rowNum) ->
                new Session(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(1),
                    instant(rs.getTimestamp(3)),
                    instant(rs.getTimestamp(4))),
            tokenHash)
        .stream()
        .findFirst();
  }

  @Override
  public void touch(String sessionId, Instant now) {
    jdbc.update(
        "UPDATE enterprise_user_session SET last_seen_at=? WHERE session_hash=?",
        timestamp(now),
        sessionId);
  }

  @Override
  public void delete(String sessionId) {
    jdbc.update("DELETE FROM enterprise_user_session WHERE session_hash=?", sessionId);
  }

  @Override
  public void deleteByUser(String userId) {
    jdbc.update("DELETE FROM enterprise_user_session WHERE user_id=?", userId);
  }

  @Override
  public int deleteExpired(Instant now) {
    return jdbc.update("DELETE FROM enterprise_user_session WHERE expires_at<=?", timestamp(now));
  }

  @Override
  public AccessPolicy accessPolicy() {
    Optional<String> value =
        jdbc
            .query(
                "SELECT setting_value FROM enterprise_setting WHERE setting_key=?",
                (rs, rowNum) -> rs.getString(1),
                UPLOAD_SETTING)
            .stream()
            .findFirst();
    boolean enabled =
        value.map(String::trim).map(v -> v.equalsIgnoreCase("true") || v.equals("1")).orElse(false);
    return new AccessPolicy(enabled);
  }

  @Override
  public void saveAccessPolicy(AccessPolicy policy) {
    jdbc.update(
        "INSERT INTO enterprise_setting(setting_key,setting_value) VALUES(?,?) ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value),updated_at=CURRENT_TIMESTAMP(6)",
        UPLOAD_SETTING,
        policy.userUploadEnabled() ? "true" : "false");
  }

  private long scalar(String sql) {
    Long value = jdbc.queryForObject(sql, Long.class);
    return value == null ? 0 : value;
  }

  private RowMapper<User> userMapper() {
    return (rs, rowNum) ->
        new User(
            rs.getString(1),
            rs.getString(2),
            rs.getString(3),
            Role.valueOf(rs.getString(4)),
            UserStatus.valueOf(rs.getString(5)),
            instant(rs.getTimestamp(6)),
            instant(rs.getTimestamp(7)),
            instant(rs.getTimestamp(8)));
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }
}
