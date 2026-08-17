package cn.lgs.knowledgebase.interfaces.http;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Hosts the existing enterprise React/Preact SPA from the Java runtime. API and actuator routes are
 * intentionally not part of this fallback.
 */
@Controller
public final class EnterpriseSpaController {
  @GetMapping("/")
  public String root() {
    return "redirect:/enterprise";
  }

  @GetMapping({"/enterprise", "/enterprise/**"})
  public String enterprise() {
    return "forward:/index.html";
  }
}
