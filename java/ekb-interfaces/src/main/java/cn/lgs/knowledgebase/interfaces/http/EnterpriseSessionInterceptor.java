package cn.lgs.knowledgebase.interfaces.http;

import cn.lgs.knowledgebase.application.identity.IdentityApplicationService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public final class EnterpriseSessionInterceptor implements HandlerInterceptor {
    public static final String SESSION_COOKIE = "ekb_session";
    private final IdentityApplicationService identity;

    public EnterpriseSessionInterceptor(IdentityApplicationService identity) {
        if (identity == null) throw new IllegalArgumentException("identity service is required");
        this.identity = identity;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = sessionToken(request);
        if (token.isEmpty()) throw new EnterpriseRequestContext.Unauthorized("user session required");
        request.setAttribute(EnterpriseRequestContext.USER_ATTRIBUTE, identity.authenticate(token));
        return true;
    }

    static String sessionToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return "";
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName())) return cookie.getValue() == null ? "" : cookie.getValue().trim();
        }
        return "";
    }
}
