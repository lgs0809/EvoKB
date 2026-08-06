package cn.lgs.knowledgebase.interfaces.http;

import cn.lgs.knowledgebase.application.access.McpSessionRepository;
import cn.lgs.knowledgebase.application.identity.IdentityApplicationService;
import cn.lgs.knowledgebase.interfaces.mcp.McpIdentityContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticate every MCP HTTP request, including SSE replay and session deletion. */
public final class EnterpriseMcpAuthenticationFilter extends OncePerRequestFilter {
  private final IdentityApplicationService identity;
  private final McpSessionRepository sessions;
  private final String cookieName;
  private final String endpoint;

  public EnterpriseMcpAuthenticationFilter(
      IdentityApplicationService identity,
      McpSessionRepository sessions,
      String cookieName,
      String endpoint) {
    this.identity = identity;
    this.sessions = sessions;
    this.cookieName = cookieName;
    this.endpoint = endpoint;
  }

  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getServletPath().equals(endpoint);
  }

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String origin = request.getHeader("Origin");
    String expectedOrigin =
        request.getScheme()
            + "://"
            + request.getServerName()
            + ((request.getServerPort() == 80 && "http".equals(request.getScheme()))
                    || (request.getServerPort() == 443 && "https".equals(request.getScheme()))
                ? ""
                : ":" + request.getServerPort());
    if (origin != null && !origin.equals(expectedOrigin)) {
      response.sendError(403, "MCP origin is not allowed");
      return;
    }
    final String userId;
    try {
      userId =
          identity
              .authenticate(EnterpriseSessionInterceptor.sessionToken(request, cookieName))
              .id();
    } catch (IdentityApplicationService.Unauthorized failure) {
      response.sendError(401, "user session required");
      return;
    }
    String sessionId = request.getHeader("Mcp-Session-Id");
    if (sessionId != null && !sessions.owned(sessionId, userId)) {
      response.sendError(404, "MCP session is unavailable to this account");
      return;
    }
    // Cached SDK replay cannot revalidate source ACLs. Optional GET streams may return 405
    // under Streamable HTTP; a new POST tool call always revalidates current authority.
    if ("GET".equals(request.getMethod())) {
      response.setHeader("Allow", "POST, DELETE");
      response.sendError(405, "MCP event replay is not available; submit a new tool call");
      return;
    }
    request.setAttribute(McpIdentityContext.USER_ATTRIBUTE, userId);
    HttpServletResponseWrapper authenticated =
        new HttpServletResponseWrapper(response) {
          private String bound;

          private void bind(String name, String value) {
            if ("Mcp-Session-Id".equalsIgnoreCase(name) && value != null && !value.equals(bound)) {
              sessions.bind(value, userId);
              bound = value;
            }
          }

          public void setHeader(String name, String value) {
            bind(name, value);
            super.setHeader(name, value);
          }

          public void addHeader(String name, String value) {
            bind(name, value);
            super.addHeader(name, value);
          }
        };
    chain.doFilter(request, authenticated);
    if ("DELETE".equals(request.getMethod()) && response.getStatus() < 400 && sessionId != null)
      sessions.remove(sessionId, userId);
  }
}
