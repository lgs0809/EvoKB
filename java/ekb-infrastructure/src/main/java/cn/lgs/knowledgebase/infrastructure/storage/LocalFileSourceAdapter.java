package cn.lgs.knowledgebase.infrastructure.storage;

import cn.lgs.knowledgebase.application.port.Ports.FileSourcePort;
import cn.lgs.knowledgebase.application.port.Ports.SourceFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class LocalFileSourceAdapter implements FileSourcePort {
    @Override
    public SourceFile inspect(String path) {
        Path file = resolve(path);
        if (!Files.isRegularFile(file)) throw new IllegalStateException("source file does not exist: " + file);
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            long size = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                digest.update(buffer, 0, read);
                size += read;
            }
            String mediaType = Files.probeContentType(file);
            if (mediaType == null || mediaType.isBlank()) mediaType = "application/octet-stream";
            return new SourceFile(file.toString(), file.getFileName().toString(), size, HexFormat.of().formatHex(digest.digest()), mediaType);
        } catch (Exception e) {
            throw new IllegalStateException("cannot inspect source file: " + file, e);
        }
    }

    @Override
    public InputStream open(String path) {
        try { return Files.newInputStream(resolve(path)); }
        catch (IOException e) { throw new IllegalStateException("cannot open source file: " + path, e); }
    }

    @Override public boolean exists(String path) { return Files.isRegularFile(resolve(path)); }

    @Override
    public void delete(String path) {
        try { Files.deleteIfExists(resolve(path)); }
        catch (IOException e) { throw new IllegalStateException("cannot delete source file: " + path, e); }
    }

    private static Path resolve(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("source path is required");
        return Path.of(path).toAbsolutePath().normalize();
    }
}
