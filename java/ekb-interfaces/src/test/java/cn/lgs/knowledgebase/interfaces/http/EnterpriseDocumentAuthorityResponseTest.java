package cn.lgs.knowledgebase.interfaces.http;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.access.KnowledgeAccessService;
import cn.lgs.knowledgebase.application.operations.EnterpriseOperations;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EnterpriseDocumentAuthorityResponseTest {
  @Test
  void currentAuthorityRejectionReturns403AndClientClearanceCannotElevateIt() throws Exception {
    var reader =
        new User(
            "reader",
            "reader",
            "Reader",
            Role.USER,
            UserStatus.ACTIVE,
            null,
            Instant.EPOCH,
            Instant.EPOCH,
            10);
    var observed = new AtomicInteger();
    var operations =
        proxy(
            EnterpriseOperations.Repository.class,
            (p, m, a) -> {
              if (!m.getName().equals("documentDetail")) throw new AssertionError(m.getName());
              observed.set((Integer) a[2]);
              return Optional.empty();
            });
    var access =
        new KnowledgeAccessService(
            proxy(UserRepository.class, (p, m, a) -> Optional.of(reader)),
            unused(EvidenceResolver.class),
            unused(QuerySessionRepository.class),
            unused(SourceVersionAuthority.class));
    var controller =
        new EnterpriseApiController(
            null,
            null,
            null,
            operations,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            access,
            256L * 1024 * 1024);
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new EnterpriseExceptionHandler())
            .build();
    var response =
        mvc.perform(
                MockMvcRequestBuilders.get("/api/enterprise/document/detail")
                    .param("version_id", "classified-version")
                    .param("security_clearance", "127")
                    .requestAttr(EnterpriseRequestContext.USER_ATTRIBUTE, reader))
            .andReturn()
            .getResponse();
    assertEquals(10, observed.get());
    assertEquals(403, response.getStatus());
    assertFalse(response.getContentAsString().contains("document version not found"));
  }

  private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }

  private static <T> T unused(Class<T> type) {
    return proxy(
        type,
        (p, m, a) -> {
          throw new AssertionError("unexpected " + m.getName());
        });
  }
}
