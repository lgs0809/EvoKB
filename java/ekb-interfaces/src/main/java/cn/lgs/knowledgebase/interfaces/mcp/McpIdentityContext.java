package cn.lgs.knowledgebase.interfaces.mcp;

import cn.lgs.knowledgebase.application.access.KnowledgeAccessService;
import io.modelcontextprotocol.common.McpTransportContext;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.web.servlet.function.ServerRequest;

/** Only the authenticated HTTP filter supplies identity; tool arguments never do. */
public final class McpIdentityContext {
  public static final String USER_ATTRIBUTE = McpIdentityContext.class.getName() + ".userId";

  private McpIdentityContext() {}

  public static McpTransportContext extract(ServerRequest request) {
    Object user = request.servletRequest().getAttribute(USER_ATTRIBUTE);
    if (!(user instanceof String id) || id.isBlank()) throw new KnowledgeAccessService.Denied();
    return McpTransportContext.create(Map.of(USER_ATTRIBUTE, id));
  }

  static String userId(ToolContext context) {
    if (context == null) throw new KnowledgeAccessService.Denied();
    Object user =
        McpToolUtils.getMcpExchange(context)
            .orElseThrow(KnowledgeAccessService.Denied::new)
            .transportContext()
            .get(USER_ATTRIBUTE);
    if (!(user instanceof String id) || id.isBlank()) throw new KnowledgeAccessService.Denied();
    return id;
  }
}
