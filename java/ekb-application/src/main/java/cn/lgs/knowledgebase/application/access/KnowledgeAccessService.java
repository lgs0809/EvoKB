package cn.lgs.knowledgebase.application.access;

import cn.lgs.knowledgebase.application.port.Ports.EvidenceResolver;
import cn.lgs.knowledgebase.application.port.Ports.QuerySessionRepository;
import cn.lgs.knowledgebase.application.port.Ports.UserRepository;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.User;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryEvidenceRef;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QuerySession;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Result;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Current account authority, shared by HTTP, async execution, history and downloads. */
public final class KnowledgeAccessService {
  public static final String UNAVAILABLE = "本次回答所依据的资料权限或版本已发生变化，请重新查询。";
  private final UserRepository users;
  private final EvidenceResolver evidence;
  private final QuerySessionRepository sessions;
  private final cn.lgs.knowledgebase.application.port.Ports.SourceVersionAuthority versions;

  public KnowledgeAccessService(
      UserRepository users,
      EvidenceResolver evidence,
      QuerySessionRepository sessions,
      cn.lgs.knowledgebase.application.port.Ports.SourceVersionAuthority versions) {
    this.users = Objects.requireNonNull(users);
    this.evidence = Objects.requireNonNull(evidence);
    this.sessions = Objects.requireNonNull(sessions);
    this.versions = Objects.requireNonNull(versions);
  }

  public int clearance(String userId) {
    return users.findById(userId).filter(User::active).orElseThrow(Denied::new).securityClearance();
  }

  public SearchFilters filters(String userId, SearchFilters requested) {
    SearchFilters base =
        requested == null ? new SearchFilters(null, null, null, List.of()) : requested;
    // Neither client filters nor a persisted session grants authority.
    return base.withClearance(clearance(userId));
  }

  public boolean permitsEvidence(String userId, List<String> ids) {
    var scope = filters(userId, null);
    if (ids == null || ids.isEmpty()) return true;
    if (ids.stream().anyMatch(id -> id == null || id.isBlank())) return false;
    var expected = ids.stream().distinct().toList();
    var resolved = evidence.resolve(expected, scope);
    return resolved != null
        && resolved.stream()
            .map(e -> e.id())
            .collect(java.util.stream.Collectors.toSet())
            .equals(new java.util.HashSet<>(expected));
  }

  public void requireEvidence(String userId, List<String> ids) {
    if (!permitsEvidence(userId, ids)) throw new Denied();
  }

  public void requireVersions(String userId, List<String> ids) {
    var scope = filters(userId, null);
    if (ids == null
        || ids.isEmpty()
        || ids.stream().anyMatch(id -> id == null || id.isBlank())
        || !versions.accessible(ids.stream().distinct().toList(), scope)) throw new Denied();
  }

  public List<Result> revalidate(String userId, List<Result> results) {
    var scope = filters(userId, null);
    var ids =
        results.stream().flatMap(r -> r.evidence().stream()).map(e -> e.id()).distinct().toList();
    var resolved = evidence.resolve(ids, scope);
    var available =
        resolved == null
            ? java.util.Set.<String>of()
            : resolved.stream().map(e -> e.id()).collect(java.util.stream.Collectors.toSet());
    return results.stream()
        .filter(
            r ->
                !r.evidence().isEmpty()
                    && r.evidence().stream().allMatch(e -> available.contains(e.id()))
                    && versions.accessible(sourceVersions(r.candidate()), scope))
        .toList();
  }

  public boolean permitsSession(QuerySession session) {
    var ids =
        Stream.concat(
                session.citationEvidenceIds().stream(),
                sessions.listEvidence(session.sessionId()).stream()
                    .map(QueryEvidenceRef::evidenceId))
            .distinct()
            .toList();
    var dependencies = sessions.listSourceVersions(session.sessionId());
    return (dependencies.isEmpty()
            || versions.accessible(dependencies, filters(session.userId(), null)))
        && permitsEvidence(session.userId(), ids);
  }

  public void requireResults(String userId, List<Result> results) {
    requireEvidence(
        userId,
        results.stream().flatMap(r -> r.evidence().stream()).map(e -> e.id()).distinct().toList());
    var dependencies =
        results.stream().flatMap(r -> sourceVersions(r.candidate()).stream()).distinct().toList();
    if (!dependencies.isEmpty() && !versions.accessible(dependencies, filters(userId, null)))
      throw new Denied();
  }

  public static List<String> sourceVersions(
      cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate candidate) {
    Object declared = candidate.attributes().get("source_version_ids");
    if (declared instanceof List<?> list)
      return list.stream().map(String::valueOf).distinct().toList();
    return candidate.versionId() == null || candidate.versionId().isBlank()
        ? List.of()
        : List.of(candidate.versionId());
  }

  public QuerySession visibleSession(QuerySession session) {
    if (permitsSession(session)) return session;
    return session.withStatus(
        session.status(), UNAVAILABLE, true, "", List.of(), List.of(), session.updatedAt());
  }

  public static final class Denied extends RuntimeException {
    public Denied() {
      super("资料不存在、已失效或当前账号无权访问");
    }
  }
}
