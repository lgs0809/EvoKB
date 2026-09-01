package cn.lgs.knowledgebase.interfaces.http;

import cn.lgs.knowledgebase.domain.identity.IdentityDomain.User;
import jakarta.servlet.http.HttpServletRequest;

final class EnterpriseRequestContext {
  static final String USER_ATTRIBUTE = EnterpriseRequestContext.class.getName() + ".user";

  private EnterpriseRequestContext() {}

  static User user(HttpServletRequest request) {
    Object value = request.getAttribute(USER_ATTRIBUTE);
    if (value instanceof User user) return user;
    throw new Unauthorized("user session required");
  }

  static User admin(HttpServletRequest request) {
    User user = user(request);
    if (!user.administrator()) throw new Forbidden("admin role required");
    return user;
  }

  static final class Unauthorized extends RuntimeException {
    Unauthorized(String message) {
      super(message);
    }
  }

  static final class Forbidden extends RuntimeException {
    Forbidden(String message) {
      super(message);
    }
  }
}
