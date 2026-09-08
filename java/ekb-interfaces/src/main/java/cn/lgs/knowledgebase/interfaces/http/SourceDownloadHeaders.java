package cn.lgs.knowledgebase.interfaces.http;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Uploaded original bytes are data, never executable same-origin browser content. */
final class SourceDownloadHeaders {
  private SourceDownloadHeaders() {}

  static void apply(HttpServletResponse response, String filename, long size) {
    String safe = filename == null ? "source" : filename.replace('\\', '/');
    safe = safe.substring(safe.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "");
    if (safe.isBlank()) safe = "source";
    if (safe.length() > 255) safe = safe.substring(0, 255);
    response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    response.setHeader(
        HttpHeaders.CONTENT_DISPOSITION,
        ContentDisposition.attachment().filename(safe, StandardCharsets.UTF_8).build().toString());
    response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
    response.setHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(size));
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
  }
}
