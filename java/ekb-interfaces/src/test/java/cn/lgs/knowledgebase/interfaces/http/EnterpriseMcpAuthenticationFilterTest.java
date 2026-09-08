package cn.lgs.knowledgebase.interfaces.http;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.access.McpSessionRepository;
import cn.lgs.knowledgebase.application.identity.IdentityApplicationService;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.*;
import cn.lgs.knowledgebase.interfaces.mcp.McpIdentityContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class EnterpriseMcpAuthenticationFilterTest {
  final Instant now = Instant.now();
  final AtomicReference<User> user =
      new AtomicReference<>(
          new User("user-a", "reader", "Reader", Role.USER, UserStatus.ACTIVE, null, now, now, 30));
  final Map<String, String> owners = new HashMap<>();

  @SuppressWarnings("unchecked")
  static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[] {type}, handler);
  }

  final IdentityApplicationService identity =
      new IdentityApplicationService(
          proxy(UserRepository.class, (p, m, a) -> Optional.of(user.get())),
          proxy(
              SessionRepository.class,
              (p, m, a) ->
                  m.getName().equals("findByTokenHash")
                      ? Optional.of(
                          new Session("login", "user-a", "hash", now.plusSeconds(3600), now))
                      : null),
          proxy(PasswordHasher.class, (p, m, a) -> null),
          proxy(TransactionPort.class, (p, m, a) -> null),
          Duration.ofHours(1));
  final McpSessionRepository sessions =
      new McpSessionRepository() {
        public void bind(String id, String uid) {
          if (owners.putIfAbsent(id, uid) != null)
            throw new IllegalStateException("session already bound");
        }

        public boolean owned(String id, String uid) {
          return uid.equals(owners.get(id));
        }

        public void remove(String id, String uid) {
          owners.remove(id, uid);
        }
      };
  final EnterpriseMcpAuthenticationFilter filter =
      new EnterpriseMcpAuthenticationFilter(identity, sessions, "target_cookie", "/mcp");

  MockHttpServletRequest request(String method, boolean loggedIn) {
    var r = new MockHttpServletRequest(method, "/mcp");
    r.setServletPath("/mcp");
    if (loggedIn) r.setCookies(new Cookie("target_cookie", "opaque-login"));
    return r;
  }

  @Test
  void anonymousTransportCannotInitializeOrReplay() throws Exception {
    for (String method : List.of("POST", "GET", "DELETE")) {
      var response = new MockHttpServletResponse();
      filter.doFilter(
          request(method, false), response, (r, s) -> fail("anonymous reached transport"));
      assertEquals(401, response.getStatus());
    }
  }

  @Test
  void foreignOrInventedProtocolSessionIsDeniedBeforeTransport() throws Exception {
    owners.put("foreign", "user-b");
    for (String id : List.of("foreign", "invented")) {
      var r = request("GET", true);
      r.addHeader("Mcp-Session-Id", id);
      r.addHeader("X-User-Id", "user-b");
      var response = new MockHttpServletResponse();
      filter.doFilter(r, response, (req, res) -> fail("session crossed accounts"));
      assertEquals(404, response.getStatus());
    }
  }

  @Test
  void frameworkGeneratedSessionBindsToAuthenticatedIdentityOnlyOnce() throws Exception {
    var r = request("POST", true);
    r.addHeader("X-User-Id", "forged");
    var response = new MockHttpServletResponse();
    filter.doFilter(
        r,
        response,
        (req, res) -> {
          assertEquals("user-a", req.getAttribute(McpIdentityContext.USER_ATTRIBUTE));
          ((HttpServletResponse) res).setHeader("Mcp-Session-Id", "protocol-1");
          ((HttpServletResponse) res).addHeader("Mcp-Session-Id", "protocol-1");
        });
    assertEquals("user-a", owners.get("protocol-1"));
    user.set(user.get().withRoleAndStatus(Role.USER, UserStatus.DISABLED, now));
    var replay = request("GET", true);
    replay.addHeader("Mcp-Session-Id", "protocol-1");
    response = new MockHttpServletResponse();
    filter.doFilter(replay, response, (req, res) -> fail("disabled account replayed"));
    assertEquals(401, response.getStatus());
  }

  @Test
  void crossOriginRequestIsDeniedAndOwnedDeleteRemovesBinding() throws Exception {
    var r = request("POST", true);
    r.addHeader("Origin", "https://untrusted.example");
    var response = new MockHttpServletResponse();
    filter.doFilter(r, response, (req, res) -> fail("cross origin reached transport"));
    assertEquals(403, response.getStatus());
    owners.put("owned", "user-a");
    r = request("DELETE", true);
    r.addHeader("Mcp-Session-Id", "owned");
    filter.doFilter(r, new MockHttpServletResponse(), (req, res) -> {});
    assertFalse(owners.containsKey("owned"));
  }

  @Test
  void cachedReplayCannotBypassCurrentSourcePermissions() throws Exception {
    owners.put("owned", "user-a");
    var r = request("GET", true);
    r.addHeader("Mcp-Session-Id", "owned");
    r.addHeader("Last-Event-ID", "cached-private-response");
    var response = new MockHttpServletResponse();
    filter.doFilter(r, response, (req, res) -> fail("cached replay reached transport"));
    assertEquals(405, response.getStatus());
    assertEquals("POST, DELETE", response.getHeader("Allow"));
  }
}
