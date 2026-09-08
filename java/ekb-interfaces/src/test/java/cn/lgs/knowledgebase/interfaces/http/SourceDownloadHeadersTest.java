package cn.lgs.knowledgebase.interfaces.http;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.mock.web.MockHttpServletResponse;

class SourceDownloadHeadersTest {
  @Test
  void originalHtmlCannotExecuteAsSameOriginContent() {
    var response = new MockHttpServletResponse();
    SourceDownloadHeaders.apply(response, "untrusted.html", 17);
    assertEquals("application/octet-stream", response.getContentType());
    assertTrue(response.getHeader("Content-Disposition").startsWith("attachment;"));
    assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
    assertEquals("sandbox; default-src 'none'", response.getHeader("Content-Security-Policy"));
    assertEquals("private, no-store", response.getHeader("Cache-Control"));
    assertEquals("17", response.getHeader("Content-Length"));
  }

  @Test
  void chineseOriginalNameSurvivesFrameworkEncoding() {
    var response = new MockHttpServletResponse();
    SourceDownloadHeaders.apply(response, "合同原件.md", 29);
    assertEquals(
        "合同原件.md",
        ContentDisposition.parse(response.getHeader("Content-Disposition")).getFilename());
  }

  @Test
  void pathAndControlCharactersCannotBecomeDownloadHeaders() {
    var response = new MockHttpServletResponse();
    SourceDownloadHeaders.apply(response, "../private\\source\r\n.txt", 0);
    assertEquals(
        "source.txt",
        ContentDisposition.parse(response.getHeader("Content-Disposition")).getFilename());
  }
}
