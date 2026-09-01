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
import cn.lgs.knowledgebase.domain.query.QueryDomain.RetrievalReservation;
import cn.lgs.knowledgebase.domain.query.QueryDomain.RetrievalRound;
import cn.lgs.knowledgebase.domain.query.QueryDomain.SessionStatus;
import cn.lgs.knowledgebase.domain.query.QueryDomain.TurnRole;
import cn.lgs.knowledgebase.domain.query.RetrievalBudget;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcQuerySessionRepository
    implements QuerySessionRepository, QueryHistoryRepository {
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final TransactionTemplate reservations;
  private final ObjectMapper mapper;

  public JdbcQuerySessionRepository(
      JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
    if (jdbc == null) throw new IllegalArgumentException("jdbc is required");
    if (transactions == null) throw new IllegalArgumentException("transactions are required");
    if (mapper == null) throw new IllegalArgumentException("object mapper is required");
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.reservations = new TransactionTemplate(transactions.getTransactionManager());
    this.reservations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.mapper = mapper;
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
  public Optional<RetrievalReservation> reserveRound(
      String userId,
      String sessionId,
      List<String> proposedQueries,
      int maxRounds,
      int maxQueries) {
    return reservations.execute(
        ignored -> {
          List<String> states =
              jdbc.query(
                  "SELECT status FROM query_session WHERE session_id=? AND user_id=? AND deleted_at IS NULL FOR UPDATE",
                  (rs, row) -> rs.getString(1),
                  sessionId,
                  userId);
          if (states.size() != 1 || !SessionStatus.RUNNING.name().equals(states.get(0)))
            throw new IllegalStateException("query session is not runnable");
          RetrievalBudget budget =
              new RetrievalBudget(listReservations(sessionId), maxRounds, maxQueries);
          List<String> queries =
              budget.freshQueries(
                  proposedQueries, proposedQueries == null ? 0 : proposedQueries.size());
          if (queries.isEmpty()) return Optional.empty();
          String id = UUID.randomUUID().toString();
          jdbc.update(
              "INSERT INTO query_retrieval_reservation(reservation_id,session_id,round_no,input_queries) VALUES(?,?,?,?::jsonb)",
              id,
              sessionId,
              budget.nextRoundNo(),
              json(queries));
          return listReservations(sessionId).stream()
              .filter(r -> r.reservationId().equals(id))
              .findFirst();
        });
  }

  @Override
  public List<RetrievalReservation> listReservations(String sessionId) {
    return jdbc.query(
        "SELECT reservation_id,session_id,round_no,input_queries,started_at FROM query_retrieval_reservation WHERE session_id=? ORDER BY round_no",
        (rs, row) ->
            new RetrievalReservation(
                rs.getString(1),
                rs.getString(2),
                rs.getInt(3),
                strings(rs.getString(4)),
                instant(rs.getTimestamp(5))),
        sessionId);
  }

  @Override
  public void saveRound(RetrievalRound round) {
    transactions.executeWithoutResult(
        ignored -> {
          RetrievalReservation reserved =
              jdbc
                  .query(
                      "SELECT reservation_id,session_id,round_no,input_queries,started_at FROM query_retrieval_reservation WHERE reservation_id=? FOR UPDATE",
                      (rs, row) ->
                          new RetrievalReservation(
                              rs.getString(1),
                              rs.getString(2),
                              rs.getInt(3),
                              strings(rs.getString(4)),
                              instant(rs.getTimestamp(5))),
                      round.roundId())
                  .stream()
                  .findFirst()
                  .orElseThrow(() -> new IllegalStateException("retrieval round was not reserved"));
          if (!reserved.sessionId().equals(round.sessionId())
              || reserved.roundNo() != round.roundNo()
              || !reserved.inputQueries().equals(round.inputQueries())
              || !reserved.startedAt().equals(round.startedAt()))
            throw new IllegalStateException("retrieval round does not match its reservation");
          int rows =
              jdbc.update(
                  "INSERT INTO retrieval_round(round_id,session_id,round_no,input_queries,decision,missing_information,next_queries,started_at,finished_at) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                  round.roundId(),
                  round.sessionId(),
                  round.roundNo(),
                  json(round.inputQueries()),
                  round.decision().name(),
                  json(round.missingInformation()),
                  json(round.nextQueries()),
                  ts(round.startedAt()),
                  ts(round.finishedAt()));
          if (rows == 0
              && listRounds(round.sessionId()).stream()
                  .noneMatch(existing -> sameCompletion(existing, round)))
            throw new IllegalStateException("retrieval round already has a different completion");
        });
  }

  private static boolean sameCompletion(RetrievalRound existing, RetrievalRound next) {
    return existing.roundId().equals(next.roundId())
        && existing.roundNo() == next.roundNo()
        && existing.inputQueries().equals(next.inputQueries())
        && existing.decision() == next.decision()
        && existing.missingInformation().equals(next.missingInformation())
        && existing.nextQueries().equals(next.nextQueries())
        && existing.startedAt().equals(next.startedAt())
        && existing
            .finishedAt()
            .equals(next.finishedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
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
        "INSERT INTO query_evidence(session_id,evidence_id,first_seen_round,supporting_query) VALUES(?,?,?,?) ON CONFLICT (session_id,evidence_id) DO UPDATE SET first_seen_round=LEAST(query_evidence.first_seen_round,EXCLUDED.first_seen_round),supporting_query=CASE WHEN query_evidence.first_seen_round>EXCLUDED.first_seen_round THEN EXCLUDED.supporting_query ELSE query_evidence.supporting_query END",
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
        "UPDATE query_group_member m SET deleted_at=? FROM query_group g WHERE g.group_id=m.group_id AND m.session_id=? AND m.deleted_at IS NULL AND g.user_id=? AND g.deleted_at IS NULL",
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

  @Override
  public void addSourceVersions(String sessionId, List<String> versionIds) {
    for (String versionId : versionIds)
      jdbc.update(
          "INSERT INTO query_source_dependency(session_id,version_id) VALUES(?,?) ON CONFLICT DO NOTHING",
          sessionId,
          versionId);
  }

  @Override
  public List<String> listSourceVersions(String sessionId) {
    return jdbc.query(
        "SELECT version_id FROM query_source_dependency WHERE session_id=? ORDER BY version_id",
        (rs, n) -> rs.getString(1),
        sessionId);
  }

  @Override
  public void addAssetExpansions(
      String sessionId,
      int roundNo,
      List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Result> results) {
    if (roundNo <= 0) throw new IllegalArgumentException("entry expansion round is required");
    if (results == null || results.isEmpty()) return;
    transactions.executeWithoutResult(
        status -> {
          var running =
              jdbc.query(
                  "SELECT session_id FROM query_session WHERE session_id=? AND status='RUNNING' AND deleted_at IS NULL FOR SHARE",
                  (rs, n) -> rs.getString(1),
                  sessionId);
          if (running.isEmpty())
            throw new IllegalStateException("entry expansion requires a running query");
          for (var result : results) {
            var candidate = result.candidate();
            if (candidate.lane()
                != cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.LaneType.WIKI) continue;
            var attributes = candidate.attributes();
            if (!attributes.containsKey("asset_version"))
              continue; // Never invent legacy attribution.
            Object value = attributes.get("asset_version");
            if (!(value instanceof Number revision)
                || revision.longValue() <= 0
                || revision.doubleValue() != revision.longValue()
                || !candidate.id().equals(attributes.get("asset_id")))
              throw new IllegalArgumentException("invalid expanded entry identity");
            for (var evidence : result.evidence()) {
              // Membership must be supported by this exact entry revision and resolved raw
              // evidence.
              int inserted =
                  jdbc.update(
                      """
              INSERT INTO query_asset_expansion(session_id,asset_id,asset_version,source_version_id,evidence_id,first_seen_round)
              SELECT ?,s.asset_id,s.asset_version,s.source_version_id,e.evidence_id,?
              FROM kb_asset_source s JOIN evidence e ON e.version_id=s.source_version_id AND e.document_id=s.document_id
              WHERE s.asset_id=? AND s.asset_version=? AND e.evidence_id=? AND e.document_id=? AND e.version_id=? AND e.active=TRUE
                AND s.evidence_ids @> jsonb_build_array(e.evidence_id)
                AND EXISTS(SELECT 1 FROM query_evidence q WHERE q.session_id=? AND q.evidence_id=e.evidence_id)
              ON CONFLICT DO NOTHING
              """,
                      sessionId,
                      roundNo,
                      candidate.id(),
                      revision.longValue(),
                      evidence.id(),
                      evidence.documentId(),
                      evidence.versionId(),
                      sessionId);
              if (inserted == 0) {
                Integer prior =
                    jdbc.queryForObject(
                        "SELECT count(*) FROM query_asset_expansion WHERE session_id=? AND asset_id=? AND asset_version=? AND evidence_id=?",
                        Integer.class,
                        sessionId,
                        candidate.id(),
                        revision.longValue(),
                        evidence.id());
                if (prior == null || prior != 1)
                  throw new IllegalArgumentException(
                      "expanded evidence is not in the entry catalogue or query inputs");
              }
            }
          }
        });
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
        "UPDATE query_group_member m SET deleted_at=? FROM query_group g WHERE g.group_id=m.group_id AND m.session_id=? AND m.deleted_at IS NULL AND g.user_id=? AND g.deleted_at IS NULL",
        ts(now),
        sessionId,
        userId);
    jdbc.update(
        "INSERT INTO query_group_member(group_id,session_id,position,created_at,deleted_at) VALUES(?,?,?,?,NULL) ON CONFLICT (group_id,session_id) DO UPDATE SET position=EXCLUDED.position,created_at=EXCLUDED.created_at,deleted_at=NULL",
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
