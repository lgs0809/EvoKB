package cn.lgs.knowledgebase.interfaces.http;

import cn.lgs.knowledgebase.application.identity.IdentityApplicationService;
import cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class EnterpriseExceptionHandler {
  @ExceptionHandler({
    EnterpriseRequestContext.Unauthorized.class,
    IdentityApplicationService.Unauthorized.class,
    IdentityApplicationService.InvalidCredentials.class
  })
  ResponseEntity<Map<String, String>> unauthorized(RuntimeException e) {
    return error(HttpStatus.UNAUTHORIZED, e.getMessage());
  }

  @ExceptionHandler({
    EnterpriseRequestContext.Forbidden.class,
    ManagedIngestionApplicationService.Forbidden.class
  })
  ResponseEntity<Map<String, String>> forbidden(RuntimeException e) {
    return error(HttpStatus.FORBIDDEN, e.getMessage());
  }

  @ExceptionHandler({
    IdentityApplicationService.NotFound.class,
    ManagedIngestionApplicationService.NotFound.class
  })
  ResponseEntity<Map<String, String>> notFound(RuntimeException e) {
    return error(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler({
    IdentityApplicationService.Conflict.class,
    ManagedIngestionApplicationService.Conflict.class
  })
  ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
    return error(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler({
    IdentityApplicationService.Validation.class,
    IdentityApplicationService.InvalidCurrentPassword.class,
    ManagedIngestionApplicationService.Validation.class,
    SafeBatchUploadStager.UploadRejected.class,
    IllegalArgumentException.class
  })
  ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
    return error(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<Map<String, String>> state(IllegalStateException e) {
    return error(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "error",
                message == null || message.isBlank() ? status.getReasonPhrase() : message));
  }
}
