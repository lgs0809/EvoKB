package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.port.Ports.QueryHistoryRepository;
import cn.lgs.knowledgebase.application.port.Ports.QuerySessionRepository;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.query.QueryDomain.Decision;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryEvidenceRef;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryGroup;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryPackage;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QuerySession;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryTurn;
import cn.lgs.knowledgebase.domain.query.QueryDomain.RetrievalRound;
import cn.lgs.knowledgebase.domain.query.QueryDomain.SessionStatus;
import cn.lgs.knowledgebase.domain.query.QueryDomain.TurnRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcQuerySessionRepository
    implements QuerySessionRepository, QueryHistoryRepository {
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper mapper;

  public JdbcQuerySessionRepository(
      JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
    if (jdbc == null) throw new IllegalArgumentException("jdbc is required");
    if (transactions == null) throw new IllegalArgumentException("transactions are required");
    if (mapper == null) throw new IllegalArgumentException("object mapper is required");
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.mapper = mapper;
    ensureSchema();
  }

  @Override
  public void createSession(QuerySession session) {
    jdbc.update(
        "INSERT INTO query_session(session_id,user_id,name,original_question,filters_json,status,final_answer,evidence_only,clarification_question,choices_json,citation_evidence_ids,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        session.sessionId(),
        session.userId(),
        session.name(),
        session.originalQuestion(),
        json(session.filters()),
        session.status().name(),
        nullable(session.finalAnswer()),
        session.evidenceOnly(),
        nullable(session.clarificationQuestion()),
        json(session.choices()),
        json(session.citationEvidenceIds()),
        ts(session.createdAt()),
        ts(session.updatedAt()));
  }

  @Override
  public void updateSession(QuerySession session) {
    int rows =
        jdbc.update(
            "UPDATE query_session SET status=?,final_answer=?,evidence_only=?,clarification_question=?,choices_json=?,citation_evidence_ids=?,updated_at=? WHERE session_id=? AND deleted_at IS NULL",
            session.status().name(),
            nullable(session.finalAnswer()),
            session.evidenceOnly(),
            nullable(session.clarificationQuestion()),
            json(session.choices()),
            json(session.citationEvidenceIds()),
            ts(session.updatedAt()),
            session.sessionId());
    if (rows == 0)
      throw new IllegalStateException("query session not found: " + session.sessionId());
  }

  @Override
  public Optional<QuerySession> findSession(String sessionId) {
    return jdbc
        .query(
            "SELECT session_id,user_id,name,original_question,filters_json,status,COALESCE(final_answer,''),evidence_only,COALESCE(clarification_question,''),choices_json,citation_evidence_ids,created_at,updated_at FROM query_session WHERE session_id=? AND deleted_at IS NULL",
            (rs, rowNum) -> scanSession(rs),
            sessionId)
        .stream()
        .findFirst();
  }

  @Override
  public List<QuerySession> listSessions(String userId, int limit) {
    int effectiveLimit = limit <= 0 ? 20 : Math.min(limit, 100);
    return jdbc.query(
        "SELECT session_id,user_id,name,original_question,filters_json,status,COALESCE(final_answer,''),evidence_only,COALESCE(clarification_question,''),choices_json,citation_evidence_ids,created_at,updated_at FROM query_session WHERE user_id=? AND deleted_at IS NULL ORDER BY updated_at DESC,session_id DESC LIMIT ?",
        (rs, rowNum) -> scanSession(rs),
        userId,
        effectiveLimit);
  }

  @Override
  public void appendTurn(QueryTurn turn) {
    jdbc.update(
        "INSERT INTO query_turn(turn_id,session_id,role,content,created_at) VALUES(?,?,?,?,?)",
        turn.turnId(),
        turn.sessionId(),
        turn.role().name(),
        turn.content(),
        ts(turn.createdAt()));
  }

  @Override
  public List<QueryTurn> listTurns(String sessionId) {
    return jdbc.query(
        "SELECT turn_id,session_id,role,content,created_at FROM query_turn WHERE session_id=? ORDER BY created_at,turn_id",
        (rs, rowNum) ->
            new QueryTurn(
                rs.getString(1),
                rs.getString(2),
                TurnRole.valueOf(rs.getString(3)),
                rs.getString(4),
                instant(rs.getTimestamp(5))),
        sessionId);
  }

  @Override
  public void saveRound(RetrievalRound round) {
    jdbc.update(
        "INSERT INTO retrieval_round(round_id,session_id,round_no,input_queries,decision,missing_information,next_queries,started_at,finished_at) VALUES(?,?,?,?,?,?,?,?,?) "
            + "ON DUPLICATE KEY UPDATE input_queries=VALUES(input_queries),decision=VALUES(decision),missing_information=VALUES(missing_information),next_queries=VALUES(next_queries),started_at=VALUES(started_at),finished_at=VALUES(finished_at)",
        round.roundId(),
        round.sessionId(),
        round.roundNo(),
        json(round.inputQueries()),
        round.decision().name(),
        json(round.missingInformation()),
        json(round.nextQueries()),
        ts(round.startedAt()),
        ts(round.finishedAt()));
  }

  @Override
  public List<RetrievalRound> listRounds(String sessionId) {
    return jdbc.query(
        "SELECT round_id,session_id,round_no,input_queries,decision,missing_information,next_queries,started_at,finished_at FROM retrieval_round WHERE session_id=? ORDER BY round_no",
        (rs, rowNum) ->
            new RetrievalRound(
                rs.getString(1),
                rs.getString(2),
                rs.getInt(3),
                strings(rs.getString(4)),
                Decision.valueOf(rs.getString(5)),
                strings(rs.getString(6)),
                strings(rs.getString(7)),
                instant(rs.getTimestamp(8)),
                instant(rs.getTimestamp(9))),
        sessionId);
  }

  @Override
  public void addEvidence(QueryEvidenceRef evidence) {
    jdbc.update(
        "INSERT INTO query_evidence(session_id,evidence_id,first_seen_round,supporting_query) VALUES(?,?,?,?) "
            + "ON DUPLICATE KEY UPDATE first_seen_round=LEAST(first_seen_round,VALUES(first_seen_round)),supporting_query=IF(first_seen_round>VALUES(first_seen_round),VALUES(supporting_query),supporting_query)",
        evidence.sessionId(),
        evidence.evidenceId(),
        evidence.firstSeenRound(),
        evidence.supportingQuery());
  }

  @Override
  public List<QueryEvidenceRef> listEvidence(String sessionId) {
    return jdbc.query(
        "SELECT session_id,evidence_id,first_seen_round,supporting_query FROM query_evidence WHERE session_id=? ORDER BY first_seen_round,evidence_id",
        (rs, rowNum) ->
            new QueryEvidenceRef(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)),
        sessionId);
  }

  @Override
  public void saveFeedback(
      String feedbackId,
      String sessionId,
      String userId,
      String rating,
      String category,
      String comment,
      Instant createdAt) {
    if (feedbackId == null || feedbackId.isBlank())
      throw new IllegalArgumentException("feedback id is required");
    String normalized = rating == null ? "" : rating.trim().toUpperCase();
    if (!List.of("POSITIVE", "NEGATIVE", "NEUTRAL").contains(normalized))
      throw new IllegalArgumentException("feedback rating must be POSITIVE, NEGATIVE or NEUTRAL");
    transactions.executeWithoutResult(
        ignored -> {
          QuerySession session =
              findSession(sessionId)
                  .orElseThrow(() -> new IllegalStateException("query session not found"));
          if (!session.userId().equals(userId))
            throw new IllegalStateException("query session not found");
          jdbc.update(
              "INSERT INTO query_feedback(feedback_id,session_id,user_id,rating,category,comment,created_at) VALUES(?,?,?,?,?,?,?)",
              feedbackId,
              sessionId,
              userId,
              normalized,
              nullable(category),
              nullable(comment),
              ts(createdAt == null ? Instant.now() : createdAt));
        });
  }

  @Override
  public void renameSession(String userId, String sessionId, String name, Instant now) {
    String normalized = requireName(name, "query record name");
    int rows =
        jdbc.update(
            "UPDATE query_session SET name=?,updated_at=? WHERE session_id=? AND user_id=? AND deleted_at IS NULL",
            normalized,
            ts(now == null ? Instant.now() : now),
            sessionId,
            userId);
    if (rows != 1) throw new IllegalStateException("query session not found");
  }

  @Override
  public void softDeleteSession(String userId, String sessionId, Instant now) {
    int rows =
        jdbc.update(
            "UPDATE query_session SET deleted_at=?,updated_at=? WHERE session_id=? AND user_id=? AND deleted_at IS NULL",
            ts(now == null ? Instant.now() : now),
            ts(now == null ? Instant.now() : now),
            sessionId,
            userId);
    if (rows != 1) throw new IllegalStateException("query session not found");
  }

  @Override
  public QueryGroup createGroup(QueryGroup group) {
    List<String> sessionIds =
        group.sessionIds().stream()
            .filter(id -> id != null && !id.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    if (sessionIds.size() < 2)
      throw new IllegalArgumentException("a query group requires at least two query records");
    return transactions.execute(
        ignored -> {
          for (String sessionId : sessionIds) ensureOwnedActiveSession(group.userId(), sessionId);
          jdbc.update(
              "INSERT INTO query_group(group_id,user_id,name,created_at,updated_at,deleted_at) VALUES(?,?,?,?,?,NULL)",
              group.groupId(),
              group.userId(),
              requireName(group.name(), "query group name"),
              ts(group.createdAt()),
              ts(group.updatedAt()));
          for (int i = 0; i < sessionIds.size(); i++) {
            moveSessionToGroupInternal(
                group.userId(), sessionIds.get(i), group.groupId(), i, group.createdAt());
          }
          return new QueryGroup(
              group.groupId(),
              group.userId(),
              group.name(),
              sessionIds,
              group.createdAt(),
              group.updatedAt());
        });
  }

  @Override
  public Optional<QueryGroup> findGroup(String userId, String groupId) {
    return jdbc
        .query(
            "SELECT group_id,user_id,name,created_at,updated_at FROM query_group WHERE group_id=? AND user_id=? AND deleted_at IS NULL",
            (rs, rowNum) ->
                new QueryGroup(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    groupSessionIds(rs.getString(1)),
                    instant(rs.getTimestamp(4)),
                    instant(rs.getTimestamp(5))),
            groupId,
            userId)
        .stream()
        .findFirst();
  }

  @Override
  public List<QueryGroup> listGroups(String userId) {
    return jdbc.query(
        "SELECT group_id,user_id,name,created_at,updated_at FROM query_group WHERE user_id=? AND deleted_at IS NULL ORDER BY updated_at DESC,group_id DESC",
        (rs, rowNum) ->
            new QueryGroup(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                groupSessionIds(rs.getString(1)),
                instant(rs.getTimestamp(4)),
                instant(rs.getTimestamp(5))),
        userId);
  }

  @Override
  public Optional<QueryGroup> groupForSession(String userId, String sessionId) {
    return jdbc
        .query(
            "SELECT g.group_id,g.user_id,g.name,g.created_at,g.updated_at FROM query_group_member m JOIN query_group g ON g.group_id=m.group_id WHERE m.session_id=? AND m.deleted_at IS NULL AND g.user_id=? AND g.deleted_at IS NULL LIMIT 1",
            (rs, rowNum) ->
                new QueryGroup(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    groupSessionIds(rs.getString(1)),
                    instant(rs.getTimestamp(4)),
                    instant(rs.getTimestamp(5))),
            sessionId,
            userId)
        .stream()
        .findFirst();
  }

  @Override
  public void moveSessionToGroup(String userId, String sessionId, String groupId, Instant now) {
    Instant changedAt = now == null ? Instant.now() : now;
    transactions.executeWithoutResult(
        ignored -> {
          ensureOwnedActiveSession(userId, sessionId);
          QueryGroup group =
              findGroup(userId, groupId)
                  .orElseThrow(() -> new IllegalStateException("query group not found"));
          moveSessionToGroupInternal(
              userId, sessionId, group.groupId(), group.sessionIds().size(), changedAt);
        });
  }

  @Override
  public void removeSessionFromGroup(String userId, String sessionId, Instant now) {
    Instant changedAt = now == null ? Instant.now() : now;
    ensureOwnedActiveSession(userId, sessionId);
    jdbc.update(
        "UPDATE query_group_member m JOIN query_group g ON g.group_id=m.group_id SET m.deleted_at=? WHERE m.session_id=? AND m.deleted_at IS NULL AND g.user_id=? AND g.deleted_at IS NULL",
        ts(changedAt),
        sessionId,
        userId);
  }

  @Override
  public QueryGroup renameGroup(String userId, String groupId, String name, Instant now) {
    Instant changedAt = now == null ? Instant.now() : now;
    int rows =
        jdbc.update(
            "UPDATE query_group SET name=?,updated_at=? WHERE group_id=? AND user_id=? AND deleted_at IS NULL",
            requireName(name, "query group name"),
            ts(changedAt),
            groupId,
            userId);
    if (rows != 1) throw new IllegalStateException("query group not found");
    return findGroup(userId, groupId)
        .orElseThrow(() -> new IllegalStateException("query group not found"));
  }

  @Override
  public void dissolveGroup(String userId, String groupId, Instant now) {
    Instant changedAt = now == null ? Instant.now() : now;
    transactions.executeWithoutResult(
        ignored -> {
          int rows =
              jdbc.update(
                  "UPDATE query_group SET deleted_at=?,updated_at=? WHERE group_id=? AND user_id=? AND deleted_at IS NULL",
                  ts(changedAt),
                  ts(changedAt),
                  groupId,
                  userId);
          if (rows != 1) throw new IllegalStateException("query group not found");
          jdbc.update(
              "UPDATE query_group_member SET deleted_at=? WHERE group_id=? AND deleted_at IS NULL",
              ts(changedAt),
              groupId);
        });
  }

  @Override
  public List<String> softDeleteGroupAndSessions(String userId, String groupId, Instant now) {
    Instant changedAt = now == null ? Instant.now() : now;
    return transactions.execute(
        ignored -> {
          QueryGroup group =
              findGroup(userId, groupId)
                  .orElseThrow(() -> new IllegalStateException("query group not found"));
          int rows =
              jdbc.update(
                  "UPDATE query_group SET deleted_at=?,updated_at=? WHERE group_id=? AND user_id=? AND deleted_at IS NULL",
                  ts(changedAt),
                  ts(changedAt),
                  groupId,
                  userId);
          if (rows != 1) throw new IllegalStateException("query group not found");
          for (String sessionId : group.sessionIds()) {
            jdbc.update(
                "UPDATE query_session SET deleted_at=?,updated_at=? WHERE session_id=? AND user_id=? AND deleted_at IS NULL",
                ts(changedAt),
                ts(changedAt),
                sessionId,
                userId);
          }
          return group.sessionIds();
        });
  }

  @Override
  public void savePackage(QueryPackage queryPackage) {
    jdbc.update(
        "INSERT INTO query_package(package_id,user_id,owner_type,owner_id,object_key,status,deleted_at,created_at) VALUES(?,?,?,?,?,?,?,?)",
        queryPackage.packageId(),
        queryPackage.userId(),
        queryPackage.ownerType(),
        queryPackage.ownerId(),
        queryPackage.objectKey(),
        queryPackage.status(),
        ts(queryPackage.deletedAt()),
        ts(queryPackage.createdAt()));
  }

  @Override
  public void markPackageStatus(String packageId, String status, Instant deletedAt) {
    jdbc.update(
        "UPDATE query_package SET status=?,deleted_at=? WHERE package_id=?",
        status,
        ts(deletedAt),
        packageId);
  }

  @Override
  public Optional<QueryPackage> latestPackage(String userId, String ownerType, String ownerId) {
    return jdbc
        .query(
            "SELECT package_id,user_id,owner_type,owner_id,object_key,status,deleted_at,created_at FROM query_package WHERE user_id=? AND owner_type=? AND owner_id=? ORDER BY created_at DESC,package_id DESC LIMIT 1",
            (rs, rowNum) -> scanPackage(rs),
            userId,
            ownerType,
            ownerId)
        .stream()
        .findFirst();
  }

  @Override
  public List<QueryPackage> packagesForOwner(String userId, String ownerType, String ownerId) {
    return jdbc.query(
        "SELECT package_id,user_id,owner_type,owner_id,object_key,status,deleted_at,created_at FROM query_package WHERE user_id=? AND owner_type=? AND owner_id=? ORDER BY created_at DESC,package_id DESC",
        (rs, rowNum) -> scanPackage(rs),
        userId,
        ownerType,
        ownerId);
  }

  private List<String> groupSessionIds(String groupId) {
    return jdbc.query(
        "SELECT m.session_id FROM query_group_member m JOIN query_session s ON s.session_id=m.session_id WHERE m.group_id=? AND m.deleted_at IS NULL AND s.deleted_at IS NULL ORDER BY m.position,m.session_id",
        (rs, rowNum) -> rs.getString(1),
        groupId);
  }

  private void ensureOwnedActiveSession(String userId, String sessionId) {
    Integer owned =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM query_session WHERE session_id=? AND user_id=? AND deleted_at IS NULL",
            Integer.class,
            sessionId,
            userId);
    if (owned == null || owned != 1) throw new IllegalStateException("query session not found");
  }

  private void moveSessionToGroupInternal(
      String userId, String sessionId, String groupId, int position, Instant now) {
    jdbc.update(
        "UPDATE query_group_member m JOIN query_group g ON g.group_id=m.group_id SET m.deleted_at=? WHERE m.session_id=? AND m.deleted_at IS NULL AND g.user_id=? AND g.deleted_at IS NULL",
        ts(now),
        sessionId,
        userId);
    jdbc.update(
        "INSERT INTO query_group_member(group_id,session_id,position,created_at,deleted_at) VALUES(?,?,?,?,NULL) ON DUPLICATE KEY UPDATE position=VALUES(position),created_at=VALUES(created_at),deleted_at=NULL",
        groupId,
        sessionId,
        Math.max(0, position),
        ts(now));
  }

  private QueryPackage scanPackage(ResultSet rs) throws SQLException {
    return new QueryPackage(
        rs.getString(1),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6),
        instant(rs.getTimestamp(7)),
        instant(rs.getTimestamp(8)));
  }

  private QuerySession scanSession(ResultSet rs) throws SQLException {
    return new QuerySession(
        rs.getString(1),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        filters(rs.getString(5)),
        SessionStatus.valueOf(rs.getString(6)),
        rs.getString(7),
        rs.getBoolean(8),
        rs.getString(9),
        strings(rs.getString(10)),
        strings(rs.getString(11)),
        instant(rs.getTimestamp(12)),
        instant(rs.getTimestamp(13)));
  }

  private SearchFilters filters(String value) {
    try {
      return mapper.readValue(value, SearchFilters.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot decode query search filters", e);
    }
  }

  private List<String> strings(String value) {
    if (value == null || value.isBlank()) return List.of();
    try {
      return mapper.readValue(value, STRING_LIST);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot decode query string list", e);
    }
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot encode query session JSON", e);
    }
  }

  private void ensureSchema() {
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS query_session ("
            + "session_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,"
            + "user_id CHAR(36) CHARACTER SET ascii NOT NULL,"
            + "name VARCHAR(255) NOT NULL,"
            + "original_question MEDIUMTEXT NOT NULL,filters_json JSON NOT NULL,"
            + "status ENUM('RUNNING','WAITING_USER','ANSWERED','INSUFFICIENT','FAILED') NOT NULL DEFAULT 'RUNNING',"
            + "final_answer MEDIUMTEXT NULL,evidence_only BOOLEAN NOT NULL DEFAULT FALSE,"
            + "clarification_question TEXT NULL,choices_json JSON NOT NULL,citation_evidence_ids JSON NOT NULL,"
            + "created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
            + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),"
            + "deleted_at TIMESTAMP(6) NULL,"
            + "KEY idx_query_session_user (user_id,created_at),KEY idx_query_session_status (status,updated_at),KEY idx_query_session_deleted (user_id,deleted_at)) ENGINE=InnoDB");
    ensureColumn(
        "query_session",
        "name",
        "ALTER TABLE query_session ADD COLUMN name VARCHAR(255) NULL AFTER user_id");
    ensureColumn(
        "query_session",
        "deleted_at",
        "ALTER TABLE query_session ADD COLUMN deleted_at TIMESTAMP(6) NULL AFTER updated_at");
    jdbc.update(
        "UPDATE query_session SET name=LEFT(original_question,255),updated_at=updated_at WHERE name IS NULL OR TRIM(name)='' ");
    jdbc.execute("ALTER TABLE query_session MODIFY COLUMN name VARCHAR(255) NOT NULL");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS query_turn ("
            + "turn_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,session_id CHAR(36) CHARACTER SET ascii NOT NULL,"
            + "role ENUM('USER','ASSISTANT','SYSTEM_EVENT') NOT NULL,content MEDIUMTEXT NOT NULL,"
            + "created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),KEY idx_query_turn_session (session_id,created_at),"
            + "CONSTRAINT fk_query_turn_session FOREIGN KEY (session_id) REFERENCES query_session(session_id) ON DELETE CASCADE) ENGINE=InnoDB");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS retrieval_round ("
            + "round_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,session_id CHAR(36) CHARACTER SET ascii NOT NULL,"
            + "round_no INT UNSIGNED NOT NULL,input_queries JSON NOT NULL,"
            + "decision ENUM('ANSWER','CONTINUE_SEARCH','ASK_USER') NOT NULL,missing_information JSON NOT NULL,next_queries JSON NOT NULL,"
            + "started_at TIMESTAMP(6) NOT NULL,finished_at TIMESTAMP(6) NOT NULL,"
            + "UNIQUE KEY uq_retrieval_round_session_no (session_id,round_no),KEY idx_retrieval_round_session (session_id,started_at),"
            + "CONSTRAINT fk_retrieval_round_session FOREIGN KEY (session_id) REFERENCES query_session(session_id) ON DELETE CASCADE) ENGINE=InnoDB");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS query_evidence ("
            + "session_id CHAR(36) CHARACTER SET ascii NOT NULL,evidence_id CHAR(36) CHARACTER SET ascii NOT NULL,"
            + "first_seen_round INT UNSIGNED NOT NULL,supporting_query TEXT NOT NULL,PRIMARY KEY (session_id,evidence_id),"
            + "KEY idx_query_evidence_evidence (evidence_id),"
            + "CONSTRAINT fk_query_evidence_session FOREIGN KEY (session_id) REFERENCES query_session(session_id) ON DELETE CASCADE) ENGINE=InnoDB");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS query_feedback ("
            + "feedback_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,session_id CHAR(36) CHARACTER SET ascii NOT NULL,"
            + "user_id CHAR(36) CHARACTER SET ascii NOT NULL,rating ENUM('POSITIVE','NEGATIVE','NEUTRAL') NOT NULL,"
            + "category VARCHAR(64) CHARACTER SET ascii NULL,comment TEXT NULL,created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
            + "KEY idx_query_feedback_session (session_id,created_at),KEY idx_query_feedback_user (user_id,created_at),"
            + "CONSTRAINT fk_query_feedback_session FOREIGN KEY (session_id) REFERENCES query_session(session_id) ON DELETE CASCADE) ENGINE=InnoDB");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS query_group ("
            + "group_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,user_id CHAR(36) CHARACTER SET ascii NOT NULL,"
            + "name VARCHAR(255) NOT NULL,created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
            + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),"
            + "deleted_at TIMESTAMP(6) NULL,KEY idx_query_group_user (user_id,updated_at),KEY idx_query_group_deleted (user_id,deleted_at)) ENGINE=InnoDB");
    ensureColumn(
        "query_group",
        "deleted_at",
        "ALTER TABLE query_group ADD COLUMN deleted_at TIMESTAMP(6) NULL AFTER updated_at");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS query_group_member ("
            + "group_id CHAR(36) CHARACTER SET ascii NOT NULL,session_id CHAR(36) CHARACTER SET ascii NOT NULL,position INT UNSIGNED NOT NULL DEFAULT 0,"
            + "created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),deleted_at TIMESTAMP(6) NULL,PRIMARY KEY(group_id,session_id),"
            + "KEY idx_query_group_member_session (session_id,deleted_at),"
            + "CONSTRAINT fk_query_group_member_group FOREIGN KEY (group_id) REFERENCES query_group(group_id) ON DELETE CASCADE,"
            + "CONSTRAINT fk_query_group_member_session FOREIGN KEY (session_id) REFERENCES query_session(session_id) ON DELETE CASCADE) ENGINE=InnoDB");
    ensureColumn(
        "query_group_member",
        "deleted_at",
        "ALTER TABLE query_group_member ADD COLUMN deleted_at TIMESTAMP(6) NULL AFTER created_at");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS query_package ("
            + "package_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,user_id CHAR(36) CHARACTER SET ascii NOT NULL,"
            + "owner_type ENUM('SESSION','GROUP') NOT NULL,owner_id CHAR(36) CHARACTER SET ascii NOT NULL,object_key VARCHAR(1024) NOT NULL,"
            + "status ENUM('AVAILABLE','DELETED','MISSING') NOT NULL DEFAULT 'AVAILABLE',deleted_at TIMESTAMP(6) NULL,"
            + "created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
            + "KEY idx_query_package_owner (user_id,owner_type,owner_id,created_at)) ENGINE=InnoDB");
    ensureColumn(
        "query_package",
        "status",
        "ALTER TABLE query_package ADD COLUMN status ENUM('AVAILABLE','DELETED','MISSING') NOT NULL DEFAULT 'AVAILABLE' AFTER object_key");
    ensureColumn(
        "query_package",
        "deleted_at",
        "ALTER TABLE query_package ADD COLUMN deleted_at TIMESTAMP(6) NULL AFTER status");
    jdbc.update(
        "UPDATE query_session SET status='FAILED',final_answer=?,evidence_only=TRUE,updated_at=CURRENT_TIMESTAMP(6) WHERE status='RUNNING'",
        "查询服务已重启，本次查询未完成，请重新发起。");
  }

  private void ensureColumn(String table, String column, String alterSql) {
    Integer columns =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
            Integer.class,
            table,
            column);
    if (columns == null || columns == 0) jdbc.execute(alterSql);
  }

  private static Timestamp ts(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static String nullable(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String requireName(String value, String label) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(label + " is required");
    String normalized = value.trim();
    if (normalized.length() > 255) throw new IllegalArgumentException(label + " is too long");
    return normalized;
  }
}
