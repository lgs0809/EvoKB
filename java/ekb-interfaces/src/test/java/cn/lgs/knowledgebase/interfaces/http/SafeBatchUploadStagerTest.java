package cn.lgs.knowledgebase.interfaces.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SafeBatchUploadStagerTest {
    @TempDir Path temp;

    @Test
    void stagesSupportedFilesAndPreservesSafeRelativePath() throws Exception {
        var stager = new SafeBatchUploadStager(temp, 1024 * 1024, 1024 * 1024, 10);
        var file = new MockMultipartFile("files", "report.pdf", "application/pdf", "pdf".getBytes(StandardCharsets.UTF_8));
        var result = stager.stage(List.of(file), List.of("finance/2026/report.pdf"), null);
        assertEquals(1, result.sourceCount());
        assertEquals(1, result.drafts().size());
        assertEquals("finance/2026/report.pdf", result.drafts().get(0).relativePath());
        assertTrue(Files.isRegularFile(Path.of(result.drafts().get(0).stagingPath())));
        assertTrue(Path.of(result.drafts().get(0).stagingPath()).startsWith(temp.toAbsolutePath().normalize()));
        stager.cleanup(result.batchDirectory());
    }

    @Test
    void rejectsZipSlipAndCleansStagingDirectory() throws Exception {
        var stager = new SafeBatchUploadStager(temp, 1024 * 1024, 1024 * 1024, 10);
        var zip = new MockMultipartFile("files", "bad.zip", "application/zip", zipOf("../../escape.pdf", "bad"));
        assertThrows(SafeBatchUploadStager.UploadRejected.class,
                () -> stager.stage(List.of(zip), List.of("bad.zip"), null));
        try (var entries = Files.list(temp)) { assertEquals(0, entries.count()); }
        assertFalse(Files.exists(temp.getParent().resolve("escape.pdf")));
    }

    @Test
    void zipSkipsUnsupportedEntriesAndExpandsSupportedFiles() throws Exception {
        var stager = new SafeBatchUploadStager(temp, 1024 * 1024, 1024 * 1024, 10);
        byte[] zipBytes = zipOf(List.of(
                new Entry("docs/a.pdf", "pdf"),
                new Entry("docs/run.exe", "exe"),
                new Entry("docs/b.docx", "docx")));
        var zip = new MockMultipartFile("files", "bundle.zip", "application/zip", zipBytes);
        var result = stager.stage(List.of(zip), List.of("folder/bundle.zip"), null);
        assertEquals(2, result.drafts().size());
        assertEquals(List.of("folder/docs/a.pdf", "folder/docs/b.docx"),
                result.drafts().stream().map(d -> d.relativePath()).toList());
    }

    @Test
    void enforcesExpandedSizeForZipAndNormalFilesAndSupportedFileCount() throws Exception {
        var normalSizeLimited = new SafeBatchUploadStager(temp.resolve("normal-size"), 1024 * 1024, 8, 10);
        var normal = new MockMultipartFile("files", "large.pdf", "application/pdf", "123456789".getBytes(StandardCharsets.UTF_8));
        assertThrows(SafeBatchUploadStager.UploadRejected.class,
                () -> normalSizeLimited.stage(List.of(normal), List.of("large.pdf"), null));

        var sizeLimited = new SafeBatchUploadStager(temp.resolve("zip-size"), 1024 * 1024, 8, 10);
        var zip = new MockMultipartFile("files", "large.zip", "application/zip", zipOf("a.pdf", "123456789"));
        assertThrows(SafeBatchUploadStager.UploadRejected.class,
                () -> sizeLimited.stage(List.of(zip), List.of("large.zip"), null));

        var countLimited = new SafeBatchUploadStager(temp.resolve("count"), 1024 * 1024, 1024 * 1024, 1);
        var two = new MockMultipartFile("files", "two.zip", "application/zip", zipOf(List.of(
                new Entry("a.pdf", "a"), new Entry("b.pdf", "b"))));
        assertThrows(SafeBatchUploadStager.UploadRejected.class,
                () -> countLimited.stage(List.of(two), List.of("two.zip"), null));
    }

    @Test
    void rejectsAbsoluteAndTraversalRelativePaths() {
        assertThrows(SafeBatchUploadStager.UploadRejected.class,
                () -> SafeBatchUploadStager.normalizeRelative("../secret.pdf", "secret.pdf"));
        assertThrows(SafeBatchUploadStager.UploadRejected.class,
                () -> SafeBatchUploadStager.normalizeRelative("C:\\temp\\secret.pdf", "secret.pdf"));
        assertThrows(SafeBatchUploadStager.UploadRejected.class,
                () -> SafeBatchUploadStager.normalizeRelative("/etc/secret.pdf", "secret.pdf"));
    }

    private static byte[] zipOf(String name, String content) throws Exception { return zipOf(List.of(new Entry(name, content))); }
    private static byte[] zipOf(List<Entry> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
    private record Entry(String name, String content) {}
}
