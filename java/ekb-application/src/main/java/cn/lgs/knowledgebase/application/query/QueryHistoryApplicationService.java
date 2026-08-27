package cn.lgs.knowledgebase.application.query;

import cn.lgs.knowledgebase.application.port.Ports.PackageAccess;
import cn.lgs.knowledgebase.application.port.Ports.QueryHistoryRepository;
import cn.lgs.knowledgebase.application.port.Ports.QuerySessionRepository;
import cn.lgs.knowledgebase.application.port.Ports.SourcePackageGroup;
import cn.lgs.knowledgebase.application.port.Ports.SourcePackageService;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryGroup;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryPackage;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QuerySession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class QueryHistoryApplicationService {
  private final QuerySessionRepository sessions;
  private final QueryHistoryRepository history;
  private final SourcePackageService packages;
  private final cn.lgs.knowledgebase.application.access.KnowledgeAccessService access;

  public QueryHistoryApplicationService(
      QuerySessionRepository sessions,
      QueryHistoryRepository history,
      SourcePackageService packages) {
    this(sessions, history, packages, null);
  }

  public QueryHistoryApplicationService(
      QuerySessionRepository sessions,
      QueryHistoryRepository history,
      SourcePackageService packages,
      cn.lgs.knowledgebase.application.access.KnowledgeAccessService access) {
    this.access = access;
    if (sessions == null) throw new IllegalArgumentException("query sessions are required");
    if (history == null) throw new IllegalArgumentException("query history repository is required");
    if (packages == null) throw new IllegalArgumentException("source package service is required");
    this.sessions = sessions;
    this.history = history;
    this.packages = packages;
  }

  private void requireCurrent(QuerySession session) {
    if (access != null && !access.permitsSession(session))
      throw new cn.lgs.knowledgebase.application.access.KnowledgeAccessService.Denied();
  }

  public List<RecordView> listRecords(String userId, int limit) {
    return sessions.listSessions(require(userId, "user id"), limit).stream()
        .map(session -> recordView(userId, session))
        .toList();
  }

  public RecordView getRecord(String userId, String sessionId) {
    QuerySession session = ownedSession(userId, sessionId);
    return recordView(userId, session);
  }

  public RecordView renameRecord(String userId, String sessionId, String name) {
    QuerySession session = ownedSession(userId, sessionId);
    QueryGroup group = history.groupForSession(userId, session.sessionId()).orElse(null);
    if (group != null) {
      deletePackageObjects(history.packagesForOwner(userId, "GROUP", group.groupId()));
    }
    history.renameSession(
        userId, session.sessionId(), requireName(name, "query record name"), Instant.now());
    return getRecord(userId, sessionId);
  }

  public void deleteRecord(String userId, String sessionId) {
    QuerySession session = ownedSession(userId, sessionId);
    QueryGroup group = history.groupForSession(userId, session.sessionId()).orElse(null);
    if (group != null) {
      deletePackageObjects(history.packagesForOwner(userId, "GROUP", group.groupId()));
    }
    deletePackageObjects(history.packagesForOwner(userId, "SESSION", session.sessionId()));
    history.softDeleteSession(userId, session.sessionId(), Instant.now());
  }

  public RecordView moveRecordToGroup(String userId, String sessionId, String groupId) {
    QuerySession session = ownedSession(userId, sessionId);
    QueryGroup current = history.groupForSession(userId, session.sessionId()).orElse(null);
    QueryGroup target = ownedGroup(userId, groupId);
    if (current != null && current.groupId().equals(target.groupId()))
      return getRecord(userId, sessionId);
    if (current != null) {
      deletePackageObjects(history.packagesForOwner(userId, "GROUP", current.groupId()));
    }
    deletePackageObjects(history.packagesForOwner(userId, "GROUP", target.groupId()));
    history.moveSessionToGroup(userId, sessionId, groupId, Instant.now());
    return getRecord(userId, sessionId);
  }

  public RecordView removeRecordFromGroup(String userId, String sessionId) {
    QuerySession session = ownedSession(userId, sessionId);
    QueryGroup current = history.groupForSession(userId, session.sessionId()).orElse(null);
    if (current == null) return getRecord(userId, sessionId);
    deletePackageObjects(history.packagesForOwner(userId, "GROUP", current.groupId()));
    history.removeSessionFromGroup(userId, sessionId, Instant.now());
    return getRecord(userId, sessionId);
  }

  public PackageView recordPackage(String userId, String sessionId, boolean regenerate) {
    QuerySession session = ownedSession(userId, sessionId);
    requireCurrent(session);
    List<QueryPackage> previousPackages =
        history.packagesForOwner(userId, "SESSION", session.sessionId());
    Optional<QueryPackage> existing = previousPackages.stream().findFirst();
    if (existing.isPresent() && !regenerate) {
      PackageView current = packageView(existing.get());
      if ("AVAILABLE".equals(current.status())) return current;
    }
    if (session.citationEvidenceIds().isEmpty())
      throw new IllegalStateException("query record has no cited source material to package");
    var published =
        packages.packageAnswerSources(userId, session.sessionId(), session.citationEvidenceIds());
    QueryPackage queryPackage =
        new QueryPackage(
            published.packageId(),
            userId,
            "SESSION",
            session.sessionId(),
            published.objectKey(),
            "AVAILABLE",
            null,
            Instant.now());
    history.savePackage(queryPackage);
    deletePackageObjects(previousPackages);
    return new PackageView(
        queryPackage.packageId(),
        "AVAILABLE",
        published.downloadUrl(),
        published.expiresAt(),
        queryPackage.createdAt());
  }

  public List<GroupView> listGroups(String userId) {
    String actor = require(userId, "user id");
    return history.listGroups(actor).stream().map(group -> groupView(actor, group)).toList();
  }

  public GroupView createGroup(String userId, String name, List<String> sessionIds) {
    String actor = require(userId, "user id");
    List<String> members = normalizeIds(sessionIds);
    if (members.size() < 2)
      throw new IllegalArgumentException("select at least two query records to create a group");
    List<String> invalidatedGroups = new ArrayList<>();
    for (String sessionId : members) {
      ownedSession(actor, sessionId);
      history
          .groupForSession(actor, sessionId)
          .map(QueryGroup::groupId)
          .filter(id -> !invalidatedGroups.contains(id))
          .ifPresent(invalidatedGroups::add);
    }
    for (String existingGroupId : invalidatedGroups) {
      deletePackageObjects(history.packagesForOwner(actor, "GROUP", existingGroupId));
    }
    Instant now = Instant.now();
    QueryGroup group =
        history.createGroup(
            new QueryGroup(
                UUID.randomUUID().toString(),
                actor,
                requireName(name, "query group name"),
                members,
                now,
                now));
    return groupView(actor, group);
  }

  public GroupView renameGroup(String userId, String groupId, String name) {
    QueryGroup group =
        history.renameGroup(
            require(userId, "user id"),
            require(groupId, "query group id"),
            requireName(name, "query group name"),
            Instant.now());
    return groupView(userId, group);
  }

  /** Dissolve removes only the group and its group-level packages; query records remain intact. */
  public void dissolveGroup(String userId, String groupId) {
    QueryGroup group = ownedGroup(userId, groupId);
    deletePackageObjects(history.packagesForOwner(userId, "GROUP", group.groupId()));
    history.dissolveGroup(userId, group.groupId(), Instant.now());
  }

  /** Delete group removes the group and every query record contained in it. */
  public void deleteGroup(String userId, String groupId) {
    QueryGroup group = ownedGroup(userId, groupId);
    List<QueryPackage> ownedPackages = new ArrayList<>();
    ownedPackages.addAll(history.packagesForOwner(userId, "GROUP", group.groupId()));
    for (String sessionId : group.sessionIds()) {
      ownedPackages.addAll(history.packagesForOwner(userId, "SESSION", sessionId));
    }
    deletePackageObjects(ownedPackages);
    history.softDeleteGroupAndSessions(userId, group.groupId(), Instant.now());
  }

  public PackageView groupPackage(String userId, String groupId, boolean regenerate) {
    QueryGroup group = ownedGroup(userId, groupId);
    for (String id : group.sessionIds()) requireCurrent(ownedSession(userId, id));
    List<QueryPackage> previousPackages =
        history.packagesForOwner(userId, "GROUP", group.groupId());
    Optional<QueryPackage> existing = previousPackages.stream().findFirst();
    if (existing.isPresent() && !regenerate) {
      PackageView current = packageView(existing.get());
      if ("AVAILABLE".equals(current.status())) return current;
    }

    List<SourcePackageGroup> sourceGroups = new ArrayList<>();
    for (String sessionId : group.sessionIds()) {
      QuerySession session = ownedSession(userId, sessionId);
      requireCurrent(session);
      if (!session.citationEvidenceIds().isEmpty()) {
        sourceGroups.add(
            new SourcePackageGroup(
                session.name(), session.citationEvidenceIds(), session.sessionId()));
      }
    }
    if (sourceGroups.isEmpty())
      throw new IllegalStateException("query group has no cited source material to package");
    var published = packages.packageGroupedSources(userId, sourceGroups);
    QueryPackage queryPackage =
        new QueryPackage(
            published.packageId(),
            userId,
            "GROUP",
            group.groupId(),
            published.objectKey(),
            "AVAILABLE",
            null,
            Instant.now());
    history.savePackage(queryPackage);
    deletePackageObjects(previousPackages);
    return new PackageView(
        queryPackage.packageId(),
        "AVAILABLE",
        published.downloadUrl(),
        published.expiresAt(),
        queryPackage.createdAt());
  }

  private RecordView recordView(String userId, QuerySession session) {
    PackageView latest = latestPackage(userId, "SESSION", session.sessionId());
    QueryGroup group = history.groupForSession(userId, session.sessionId()).orElse(null);
    var visible = access == null ? session : access.visibleSession(session);
    return new RecordView(visible, group, latest, visible != session);
  }

  private GroupView groupView(String userId, QueryGroup group) {
    List<RecordView> records =
        group.sessionIds().stream()
            .map(id -> recordView(userId, ownedSession(userId, id)))
            .toList();
    PackageView latest = latestPackage(userId, "GROUP", group.groupId());
    return new GroupView(group, records, latest);
  }

  private PackageView latestPackage(String userId, String ownerType, String ownerId) {
    Optional<QueryPackage> latest = history.latestPackage(userId, ownerType, ownerId);
    return latest.map(this::packageView).orElse(null);
  }

  private PackageView packageView(QueryPackage queryPackage) {
    if (!"AVAILABLE".equals(queryPackage.status())) {
      return new PackageView(
          queryPackage.packageId(), queryPackage.status(), null, null, queryPackage.createdAt());
    }
    try {
      PackageAccess access =
          packages.accessPackage(queryPackage.userId(), queryPackage.objectKey());
      return new PackageView(
          queryPackage.packageId(),
          "AVAILABLE",
          access.downloadUrl(),
          access.expiresAt(),
          queryPackage.createdAt());
    } catch (cn.lgs.knowledgebase.application.access.KnowledgeAccessService.Denied denied) {
      return new PackageView(
          queryPackage.packageId(), "ACCESS_CHANGED", null, null, queryPackage.createdAt());
    } catch (RuntimeException unavailable) {
      history.markPackageStatus(queryPackage.packageId(), "MISSING", null);
      return new PackageView(
          queryPackage.packageId(), "MISSING", null, null, queryPackage.createdAt());
    }
  }

  private QuerySession ownedSession(String userId, String sessionId) {
    String actor = require(userId, "user id");
    QuerySession session =
        sessions
            .findSession(require(sessionId, "query session id"))
            .orElseThrow(() -> new IllegalStateException("query session not found"));
    if (!session.userId().equals(actor)) throw new IllegalStateException("query session not found");
    return session;
  }

  private QueryGroup ownedGroup(String userId, String groupId) {
    return history
        .findGroup(require(userId, "user id"), require(groupId, "query group id"))
        .orElseThrow(() -> new IllegalStateException("query group not found"));
  }

  private void deletePackageObjects(List<QueryPackage> queryPackages) {
    Instant now = Instant.now();
    for (QueryPackage queryPackage : queryPackages) {
      if (!"AVAILABLE".equals(queryPackage.status())) continue;
      try {
        packages.deletePackage(queryPackage.objectKey());
        history.markPackageStatus(queryPackage.packageId(), "DELETED", now);
      } catch (RuntimeException ignored) {
        history.markPackageStatus(queryPackage.packageId(), "MISSING", null);
        // Package blobs are derived artifacts. History deletion must not be blocked by a stale
        // blob.
      }
    }
  }

  private static List<String> normalizeIds(List<String> values) {
    if (values == null) return List.of();
    return values.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::trim)
        .distinct()
        .toList();
  }

  private static String require(String value, String label) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(label + " is required");
    return value.trim();
  }

  private static String requireName(String value, String label) {
    String normalized = require(value, label);
    if (normalized.length() > 255) throw new IllegalArgumentException(label + " is too long");
    return normalized;
  }

  public record PackageView(
      String packageId, String status, String downloadUrl, Instant expiresAt, Instant createdAt) {}

  public record RecordView(
      QuerySession session, QueryGroup group, PackageView latestPackage, boolean evidenceChanged) {}

  public record GroupView(QueryGroup group, List<RecordView> records, PackageView latestPackage) {
    public GroupView {
      records = records == null ? List.of() : List.copyOf(records);
    }
  }
}
