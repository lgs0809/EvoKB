package cn.lgs.knowledgebase.application.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.access.KnowledgeAccessService;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.*;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ArtifactEvaluationAccessTest {
  final Instant now = Instant.now();
  final AtomicReference<User> user =
      new AtomicReference<>(
          new User("admin", "reader", "Reader", Role.ADMIN, UserStatus.ACTIVE, null, now, now, 30));
  final Set<String> active = new HashSet<>(Set.of("public", "secret"));

  @SuppressWarnings("unchecked")
  static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[] {type}, handler);
  }

  final KnowledgeAccessService access =
      new KnowledgeAccessService(
          proxy(UserRepository.class, (p, m, a) -> Optional.of(user.get())),
          proxy(EvidenceResolver.class, (p, m, a) -> List.of()),
          proxy(
              QuerySessionRepository.class,
              (p, m, a) -> {
                throw new AssertionError("evaluation must use artifact inputs");
              }),
          (ids, scope) ->
              ids.stream()
                  .allMatch(
                      id ->
                          active.contains(id)
                              && (id.equals("public") || scope.securityClearance() >= 30)));
  final ArtifactGoldenCase golden =
      new ArtifactGoldenCase("case", List.of("public"), List.of(), List.of(), List.of(), List.of());

  ArtifactEvaluationApplicationService service(Runnable duringRead, List<String> inputs) {
    return new ArtifactEvaluationApplicationService(
        new ArtifactEvaluationApplicationService.ArtifactRepository() {
          public ActualArtifacts load(List<String> ids) {
            throw new AssertionError("unscoped path must never execute");
          }

          public ArtifactEvaluationApplicationService.ArtifactSnapshot loadScoped(
              List<String> ids, int level) {
            duringRead.run();
            return new ArtifactEvaluationApplicationService.ArtifactSnapshot(
                new ActualArtifacts(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(new WikiClaimActual("synthetic derived fact", true))),
                inputs);
          }
        });
  }

  @Test
  void accountRevocationDuringReadRejectsUncitedPrivateInputsAtDelivery() {
    var service =
        service(
            () -> user.set(user.get().withSecurityClearance(10, now)), List.of("public", "secret"));
    assertThrows(
        KnowledgeAccessService.Denied.class,
        () -> service.evaluate(List.of(golden), "admin", access));
  }

  @Test
  void sourceRevocationDuringReadRejectsSnapshotAtDelivery() {
    var service = service(() -> active.remove("secret"), List.of("public", "secret"));
    assertThrows(
        KnowledgeAccessService.Denied.class,
        () -> service.evaluate(List.of(golden), "admin", access));
  }

  @Test
  void publicInputEvaluationRemainsAvailableAtPublicClearance() {
    user.set(user.get().withSecurityClearance(10, now));
    assertNotNull(service(() -> {}, List.of("public")).evaluate(List.of(golden), "admin", access));
  }
}
