package cn.lgs.knowledgebase.interfaces.http;

import cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService.StagedDraft;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class SafeBatchUploadStager {
    public static final long DEFAULT_MAX_UPLOAD_BYTES = 4L << 30;
    public static final long DEFAULT_MAX_EXPANDED_BYTES = 16L << 30;
    public static final int DEFAULT_MAX_FILES = 2000;
    private static final Set<String> SUPPORTED = Set.of(
            ".pdf", ".doc", ".docx", ".ppt", ".pptx", ".xls", ".xlsx",
            ".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tif", ".tiff", ".md", ".txt");

    private final Path root;
    private final long maxUploadBytes;
    private final long maxExpandedBytes;
    private final int maxFiles;

    public SafeBatchUploadStager(Path root, long maxUploadBytes, long maxExpandedBytes, int maxFiles) {
        this.root = root.toAbsolutePath().normalize();
        this.maxUploadBytes = maxUploadBytes <= 0 ? DEFAULT_MAX_UPLOAD_BYTES : maxUploadBytes;
        this.maxExpandedBytes = maxExpandedBytes <= 0 ? DEFAULT_MAX_EXPANDED_BYTES : maxExpandedBytes;
        this.maxFiles = maxFiles <= 0 ? DEFAULT_MAX_FILES : maxFiles;
        try { Files.createDirectories(this.root); }
        catch (IOException e) { throw new IllegalStateException("cannot initialize ingestion staging directory", e); }
    }

    public StageResult stage(List<MultipartFile> uploads, List<String> relativePaths, Defaults defaults) {
        if (uploads == null || uploads.isEmpty()) throw new UploadRejected("at least one file is required");
        long declaredBytes = 0;
        for (MultipartFile upload : uploads) {
            if (upload == null || upload.isEmpty()) continue;
            long size = upload.getSize();
            if (size > 0 && declaredBytes > maxUploadBytes - size) throw new UploadRejected("batch upload exceeds maximum compressed/upload size");
            declaredBytes += Math.max(0, size);
        }
        if (declaredBytes > maxUploadBytes) throw new UploadRejected("batch upload exceeds maximum compressed/upload size");

        Path batchDir = root.resolve("stage-" + UUID.randomUUID()).normalize();
        requireInsideRoot(batchDir);
        try { Files.createDirectories(batchDir); }
        catch (IOException e) { throw new IllegalStateException("cannot create ingestion staging directory", e); }

        Counters counters = new Counters();
        List<StagedDraft> drafts = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        try {
            for (int i = 0; i < uploads.size(); i++) {
                MultipartFile upload = uploads.get(i);
                if (upload == null || upload.isEmpty()) continue;
                String original = safeFilename(upload.getOriginalFilename());
                String suppliedRelative = relativePaths != null && i < relativePaths.size() ? relativePaths.get(i) : original;
                String relative = normalizeRelative(suppliedRelative, original);
                if (extension(original).equals(".zip")) {
                    try (InputStream input = new BufferedInputStream(upload.getInputStream())) {
                        expandZip(input, relative, batchDir, defaults, drafts, rejected, counters);
                    }
                } else if (SUPPORTED.contains(extension(original))) {
                    counters.addFile(upload.getSize());
                    Path destination = destination(batchDir, relative);
                    Files.createDirectories(destination.getParent());
                    try (InputStream input = upload.getInputStream()) {
                        copyBounded(input, destination, counters, true);
                    }
                    drafts.add(draft(relative, original, destination, defaults));
                } else {
                    rejected.add(relative);
                }
            }
            if (drafts.isEmpty()) throw new UploadRejected("batch contains no supported files");
            return new StageResult(uploads.size(), List.copyOf(drafts), List.copyOf(rejected), batchDir);
        } catch (RuntimeException | IOException e) {
            deleteTree(batchDir);
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("staging upload failed", e);
        }
    }

    public void cleanup(Path batchDirectory) {
        if (batchDirectory == null) return;
        Path normalized = batchDirectory.toAbsolutePath().normalize();
        requireInsideRoot(normalized);
        if (normalized.equals(root)) throw new IllegalArgumentException("refusing to delete staging root");
        deleteTree(normalized);
    }

    private void expandZip(InputStream input, String zipRelativePath, Path batchDir, Defaults defaults,
                           List<StagedDraft> drafts, List<String> rejected, Counters counters) throws IOException {
        String parent = parent(zipRelativePath);
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                if (entryName.isBlank() || entry.isDirectory()) { zip.closeEntry(); continue; }
                String relative = normalizeRelative(parent.isEmpty() ? entryName : parent + "/" + entryName, entryName);
                String ext = extension(relative);
                if (!SUPPORTED.contains(ext)) { rejected.add(relative); zip.closeEntry(); continue; }
                counters.addFile(Math.max(0, entry.getSize()));
                Path destination = destination(batchDir, relative);
                Files.createDirectories(destination.getParent());
                copyBounded(zip, destination, counters, true);
                drafts.add(draft(relative, Path.of(relative).getFileName().toString(), destination, defaults));
                zip.closeEntry();
            }
        }
    }

    private void copyBounded(InputStream input, Path destination, Counters counters, boolean countExpandedDuringCopy) throws IOException {
        Path temp = destination.resolveSibling(destination.getFileName() + ".part-" + UUID.randomUUID());
        long copied = 0;
        try (OutputStream output = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                copied += read;
                if (countExpandedDuringCopy) counters.addExpanded(read);
                else if (copied > maxUploadBytes) throw new UploadRejected("uploaded file exceeds batch size limit");
                output.write(buffer, 0, read);
            }
        } catch (RuntimeException | IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private StagedDraft draft(String relative, String original, Path destination, Defaults defaults) {
        Defaults d = defaults == null ? Defaults.empty() : defaults;
        String logical = basenameWithoutExtension(original);
        return new StagedDraft(relative, original, destination.toString(), logical,
                d.sourceDepartment(), d.materialCategory(), d.company(), d.documentType(), d.documentDate(), d.manualTags());
    }

    private Path destination(Path batchDir, String relative) {
        Path path = batchDir.resolve(relative).normalize();
        if (!path.startsWith(batchDir)) throw new UploadRejected("invalid upload path");
        requireInsideRoot(path);
        return path;
    }

    static String normalizeRelative(String supplied, String fallback) {
        String value = supplied == null || supplied.isBlank() ? fallback : supplied;
        value = value.replace('\\', '/').trim();
        if (value.indexOf('\0') >= 0 || value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw new UploadRejected("invalid upload path");
        }
        if (value.isBlank()) value = fallback;
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.startsWith("..") || path.toString().equals("..")) throw new UploadRejected("invalid upload path");
        String normalized = path.toString().replace('\\', '/');
        if (normalized.isBlank() || normalized.equals(".")) throw new UploadRejected("invalid upload path");
        return normalized;
    }

    private void requireInsideRoot(Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(root)) throw new UploadRejected("staging path escapes configured root");
    }

    private static String safeFilename(String filename) {
        String value = filename == null ? "" : filename.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        value = value.trim();
        if (value.isBlank() || value.equals(".") || value.equals("..")) throw new UploadRejected("invalid filename");
        return value;
    }

    private static String extension(String value) {
        String name = value == null ? "" : value.toLowerCase(Locale.ROOT);
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        return dot > slash ? name.substring(dot) : "";
    }

    private static String basenameWithoutExtension(String value) {
        String name = safeFilename(value);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String parent(String value) {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? "" : value.substring(0, slash);
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    private final class Counters {
        private int files;
        private long expanded;
        void addFile(long declaredSize) {
            files++;
            if (files > maxFiles) throw new UploadRejected("batch contains too many supported files");
            if (declaredSize > 0 && expanded > maxExpandedBytes - declaredSize) throw new UploadRejected("expanded batch exceeds maximum size");
        }
        void addExpanded(long bytes) {
            if (bytes <= 0) return;
            if (expanded > maxExpandedBytes - bytes) throw new UploadRejected("expanded batch exceeds maximum size");
            expanded += bytes;
        }
    }

    public record Defaults(String sourceDepartment, String materialCategory, String company, String documentType,
                           java.time.LocalDate documentDate, List<String> manualTags) {
        public Defaults { manualTags = manualTags == null ? List.of() : List.copyOf(manualTags); }
        static Defaults empty() { return new Defaults(null, null, null, null, null, List.of()); }
    }
    public record StageResult(int sourceCount, List<StagedDraft> drafts, List<String> rejected, Path batchDirectory) {
        public StageResult {
            drafts = drafts == null ? List.of() : List.copyOf(drafts);
            rejected = rejected == null ? List.of() : List.copyOf(rejected);
        }
    }
    public static final class UploadRejected extends RuntimeException { public UploadRejected(String message) { super(message); } }
}
