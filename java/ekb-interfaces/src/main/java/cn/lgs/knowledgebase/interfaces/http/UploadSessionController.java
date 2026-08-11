package cn.lgs.knowledgebase.interfaces.http;

import static cn.lgs.knowledgebase.application.upload.MultipartUploadContracts.*;

import cn.lgs.knowledgebase.application.upload.MultipartUploadApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/enterprise/upload-sessions")
public final class UploadSessionController {
  private final MultipartUploadApplicationService uploads;

  public UploadSessionController(MultipartUploadApplicationService uploads) {
    this.uploads = uploads;
  }

  @GetMapping("/policy")
  public Policy policy(HttpServletRequest request) {
    return uploads.policy(EnterpriseRequestContext.user(request));
  }

  @GetMapping
  public Map<String, Object> list(HttpServletRequest request) {
    return Map.of("sessions", uploads.list(EnterpriseRequestContext.user(request)));
  }

  @PostMapping
  public ResponseEntity<Session> create(@RequestBody Create input, HttpServletRequest request) {
    return ResponseEntity.status(201)
        .body(uploads.create(EnterpriseRequestContext.user(request), input));
  }

  @GetMapping("/{id}")
  public View view(@PathVariable String id, HttpServletRequest request) {
    return uploads.view(EnterpriseRequestContext.user(request), id);
  }

  public record Sign(int number, String contentMd5, String clientSha256) {}

  @PostMapping("/{id}/part-url")
  public PartUrl sign(
      @PathVariable String id, @RequestBody Sign input, HttpServletRequest request) {
    return uploads.sign(
        EnterpriseRequestContext.user(request),
        id,
        input.number(),
        input.contentMd5(),
        input.clientSha256());
  }

  public record Complete(long revision, List<Part> parts) {}

  @PostMapping("/{id}/complete")
  public Session complete(
      @PathVariable String id, @RequestBody Complete input, HttpServletRequest request) {
    return uploads.complete(
        EnterpriseRequestContext.user(request), id, input.revision(), input.parts());
  }

  public record Revision(long revision) {}

  @PostMapping("/{id}/cancel")
  public Session cancel(
      @PathVariable String id, @RequestBody Revision input, HttpServletRequest request) {
    return uploads.cancel(EnterpriseRequestContext.user(request), id, input.revision());
  }
}
