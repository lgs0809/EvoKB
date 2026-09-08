package cn.lgs.knowledgebase.interfaces.http;

import cn.lgs.knowledgebase.application.identity.IdentityApplicationService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public final class EnterpriseSessionInterceptor implements HandlerInterceptor {
  public static final String SESSION_COOKIE = "ekb_session";
  private final IdentityApplicationService identity;
  private final String cookieName;

  public EnterpriseSessionInterceptor(IdentityApplicationService identity) {
    this(identity, SESSION_COOKIE);
  }

  public EnterpriseSessionInterceptor(IdentityApplicationService identity, String cookieName) {
    if (identity == null) throw new IllegalArgumentException("identity service is required");
    if (cookieName == null || !cookieName.matches("[A-Za-z0-9_-]{1,64}"))
      throw new IllegalArgumentException("invalid session cookie name");
    this.identity = identity;
    this.cookieName = cookieName;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    String token = sessionToken(request, cookieName);
    if (token.isEmpty()) throw new EnterpriseRequestContext.Unauthorized("user session required");
    request.setAttribute(EnterpriseRequestContext.USER_ATTRIBUTE, identity.authenticate(token));
    return true;
  }

  static String sessionToken(HttpServletRequest request) {
    return sessionToken(request, SESSION_COOKIE);
  }

  static String sessionToken(HttpServletRequest request, String cookieName) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) return "";
    for (Cookie cookie : cookies) {
      if (cookieName.equals(cookie.getName()))
        return cookie.getValue() == null ? "" : cookie.getValue().trim();
    }
    return "";
  }
}
