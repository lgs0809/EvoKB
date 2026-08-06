package cn.lgs.knowledgebase.application.access;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.*;
import cn.lgs.knowledgebase.domain.query.QueryDomain.*;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KnowledgeAccessServiceTest {
  final Instant now = Instant.now();
  final AtomicReference<User> user =
      new AtomicReference<>(
          new User("u", "reader", "Reader", Role.USER, UserStatus.ACTIVE, null, now, now, 30));
  final Set<String> active = new HashSet<>(Set.of("public", "secret"));
  final UserRepository users =
      (UserRepository)
          Proxy.newProxyInstance(
              UserRepository.class.getClassLoader(),
              new Class[] {UserRepository.class},
              (p, m, a) -> Optional.of(user.get()));
  final QuerySessionRepository sessions =
      (QuerySessionRepository)
          Proxy.newProxyInstance(
              QuerySessionRepository.class.getClassLoader(),
              new Class[] {QuerySessionRepository.class},
              (p, m, a) ->
                  switch (m.getName()) {
                    case "listEvidence" ->
                        List.of(
                            new QueryEvidenceRef("q", "public", 1, "query"),
                            new QueryEvidenceRef("q", "secret", 1, "query"));
                    case "listSourceVersions" -> List.of("public", "secret");
                    default -> throw new UnsupportedOperationException();
                  });
  final EvidenceResolver evidence =
      new EvidenceResolver() {
        public List<Evidence> resolve(List<String> ids) {
          throw new AssertionError("scope must be explicit");
        }

        public List<Evidence> resolve(List<String> ids, SearchFilters filters) {
          return ids.stream()
              .filter(
                  id ->
                      active.contains(id)
                          && (id.equals("public") || filters.securityClearance() >= 30))
              .map(
                  id ->
                      new Evidence(
                          id, "doc-" + id, id, "block-" + id, "key", Map.of(), "原文", "hash"))
              .toList();
        }
      };
  final KnowledgeAccessService access =
      new KnowledgeAccessService(
          users,
          evidence,
          sessions,
          (ids, f) ->
              ids.stream()
                  .allMatch(
                      id ->
                          active.contains(id)
                              && (id.equals("public") || f.securityClearance() >= 30)));

  @Test
  void forgedOrPersistedClearanceNeverGrantsAuthority() {
    user.set(user.get().withSecurityClearance(10, now));
    assertEquals(
        10,
        access
            .filters("u", new SearchFilters("company", "department", "type", List.of("tag"), 127))
            .securityClearance());
    assertFalse(access.permitsEvidence("u", List.of("secret")));
    assertThrows(
        KnowledgeAccessService.Denied.class, () -> access.requireEvidence("u", List.of("secret")));
  }

  @Test
  void historyUsesUncitedInputsAndReflectsRevocationWithoutRewritingSavedAnswer() {
    QuerySession original =
        new QuerySession(
            "q",
            "u",
            "question",
            "question",
            null,
            SessionStatus.ANSWERED,
            "保密信息",
            false,
            "",
            List.of(),
            List.of("public"),
            now,
            now);
    assertEquals("保密信息", access.visibleSession(original).finalAnswer());
    user.set(user.get().withSecurityClearance(10, now));
    assertEquals(KnowledgeAccessService.UNAVAILABLE, access.visibleSession(original).finalAnswer());
    assertTrue(access.visibleSession(original).citationEvidenceIds().isEmpty());
    assertEquals("保密信息", original.finalAnswer());
  }

  @Test
  void derivedCandidateIsRejectedAsAWholeWhenAnUncitedBuildInputIsRevoked() {
    Result result =
        new Result(
            new Candidate(
                "derived",
                "doc-public",
                "public",
                LaneType.WIKI,
                "summary",
                1d,
                List.of("public"),
                Map.of("source_version_ids", List.of("public", "secret"))),
            evidence.resolve(
                List.of("public"), new SearchFilters(null, null, null, List.of(), 30)));
    assertEquals(1, access.revalidate("u", List.of(result)).size());
    active.remove("secret");
    assertTrue(access.revalidate("u", List.of(result)).isEmpty());
    assertThrows(
        KnowledgeAccessService.Denied.class, () -> access.requireResults("u", List.of(result)));
  }

  @Test
  void disabledAccountFailsClosedEvenForPublicOrEmptyEvidence() {
    user.set(user.get().withRoleAndStatus(Role.USER, UserStatus.DISABLED, now));
    assertThrows(KnowledgeAccessService.Denied.class, () -> access.permitsEvidence("u", List.of()));
  }

  @Test
  void adminRoleDoesNotElevateDocumentClearance() {
    user.set(
        user.get()
            .withRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE, now)
            .withSecurityClearance(10, now));
    assertFalse(access.permitsEvidence("u", List.of("secret")));
  }

  @Test
  void compilationRequiresEveryInputAndRejectsUnknownOrEmptyInputs() {
    access.requireVersions("u", List.of("public", "secret"));
    assertThrows(KnowledgeAccessService.Denied.class, () -> access.requireVersions("u", List.of()));
    assertThrows(
        KnowledgeAccessService.Denied.class,
        () -> access.requireVersions("u", List.of("public", "missing")));
    user.set(user.get().withSecurityClearance(10, now));
    access.requireVersions("u", List.of("public"));
    assertThrows(
        KnowledgeAccessService.Denied.class,
        () -> access.requireVersions("u", List.of("public", "secret")));
  }
}
