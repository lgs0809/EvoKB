package cn.lgs.knowledgebase.interfaces.http;

import cn.lgs.knowledgebase.application.port.Ports.AuditEvent;
import cn.lgs.knowledgebase.application.port.Ports.AuditPort;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.servlet.HandlerInterceptor;

public final class EnterpriseAuditInterceptor implements HandlerInterceptor {
  private static final String REQUEST_ID_ATTRIBUTE =
      EnterpriseAuditInterceptor.class.getName() + ".requestId";
  private final AuditPort audit;
  private final boolean trustProxyHeaders;

  public EnterpriseAuditInterceptor(AuditPort audit, boolean trustProxyHeaders) {
    if (audit == null) throw new IllegalArgumentException("audit port is required");
    this.audit = audit;
    this.trustProxyHeaders = trustProxyHeaders;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    String requestId = trim(request.getHeader("X-Request-Id"));
    if (requestId.isEmpty()) requestId = UUID.randomUUID().toString();
    request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
    response.setHeader("X-Request-Id", requestId);
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    String action = action(request.getMethod(), request.getRequestURI());
    if (action.isEmpty()) return;
    try {
      User actor =
          request.getAttribute(EnterpriseRequestContext.USER_ATTRIBUTE) instanceof User user
              ? user
              : null;
      String targetId =
          firstNonBlank(
              request.getParameter("id"),
              request.getParameter("version_id"),
              request.getParameter("wiki_id"));
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("method", request.getMethod());
      details.put("path", request.getRequestURI());
      details.put("http_status", response.getStatus());
      audit.record(
          new AuditEvent(
              actor == null ? "" : actor.id(),
              actor == null ? "anonymous" : actor.username(),
              actor == null ? "" : actor.role().name(),
              action,
              targetType(action),
              targetId,
              "",
              response.getStatus() < 400 && ex == null ? "SUCCESS" : "FAILURE",
              String.valueOf(request.getAttribute(REQUEST_ID_ATTRIBUTE)),
              clientIp(request),
              trim(request.getHeader("User-Agent")),
              details,
              Instant.now()));
    } catch (RuntimeException ignored) {
      // Auditing must never turn a completed product operation into an HTTP failure.
    }
  }

  private String clientIp(HttpServletRequest request) {
    if (trustProxyHeaders) {
      String forwarded = trim(request.getHeader("X-Forwarded-For"));
      if (!forwarded.isEmpty()) {
        int comma = forwarded.indexOf(',');
        return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
      }
    }
    return trim(request.getRemoteAddr());
  }

  private static String action(String method, String path) {
    String key = method + " " + path;
    return switch (key) {
      case "POST /api/enterprise/auth/setup" -> "auth.setup";
      case "POST /api/enterprise/auth/login" -> "auth.login";
      case "POST /api/enterprise/auth/logout" -> "auth.logout";
      case "POST /api/enterprise/auth/password" -> "auth.password.change";
      case "POST /api/enterprise/users" -> "user.create";
      case "PATCH /api/enterprise/user" -> "user.update";
      case "POST /api/enterprise/user/password/reset" -> "user.password.reset";
      case "PATCH /api/enterprise/access" -> "access.settings.update";
      case "PUT /api/enterprise/model-configuration" -> "model_configuration.update";
      case "POST /api/enterprise/upload" -> "document.upload.create";
      case "POST /api/enterprise/batches" -> "ingestion.batch.create";
      case "PATCH /api/enterprise/batch/file" -> "ingestion.file.metadata.update";
      case "PATCH /api/enterprise/document" -> "document.metadata.update";
      case "POST /api/enterprise/batch/start" -> "ingestion.batch.start";
      case "POST /api/enterprise/batch/file/retry" -> "ingestion.file.retry";
      case "POST /api/enterprise/generation/recommend", "POST /api/enterprise/distill" ->
          "knowledge_generation.recommend";
      case "GET /api/enterprise/evidence/source", "GET /api/enterprise/evidence/source/file" ->
          "evidence.source.view";
      case "POST /api/enterprise/package" -> "evidence.package.create";
      case "POST /api/enterprise/evaluation/artifacts" -> "evaluation.artifact.run";
      case "POST /api/enterprise/compilation/summary" -> "compilation.summary.run";
      case "POST /api/enterprise/compilation/graph" -> "compilation.graph.run";
      case "POST /api/enterprise/compilation/wiki" -> "compilation.wiki.run";
      case "POST /api/enterprise/compilation/wiki-keywords" -> "compilation.wiki_keyword.run";
      default -> "";
    };
  }

  private static String targetType(String action) {
    if (action.startsWith("user.")) return "user";
    if (action.startsWith("ingestion.batch")) return "ingestion_batch";
    if (action.startsWith("ingestion.file")) return "ingestion_file";
    if (action.startsWith("document.")) return "document_version";
    if (action.startsWith("knowledge_generation.") || action.startsWith("distill."))
      return "document_version";
    if (action.startsWith("evidence.")) return "evidence";
    if (action.startsWith("evaluation.")) return "evaluation";
    if (action.startsWith("compilation.")) return "knowledge_artifact";
    if (action.startsWith("access.")) return "settings";
    if (action.startsWith("model_configuration.")) return "model_configuration";
    return "auth";
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      String v = trim(value);
      if (!v.isEmpty()) return v;
    }
    return "";
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
