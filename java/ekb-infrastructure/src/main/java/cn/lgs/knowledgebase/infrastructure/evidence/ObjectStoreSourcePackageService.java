package cn.lgs.knowledgebase.infrastructure.evidence;

import cn.lgs.knowledgebase.application.operations.EnterpriseOperations.EvidenceSourceRef;
import cn.lgs.knowledgebase.application.operations.EnterpriseOperations.Repository;
import cn.lgs.knowledgebase.application.port.Ports.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ObjectStoreSourcePackageService implements SourcePackageService {
  private final Repository repository;
  private final ObjectStore objects;
  private final ObjectMapper mapper;
  private final Duration expiry;
  private final Clock clock;

  public ObjectStoreSourcePackageService(
      Repository repository, ObjectStore objects, ObjectMapper mapper, Duration expiry) {
    this(repository, objects, mapper, expiry, Clock.systemUTC());
  }

  ObjectStoreSourcePackageService(
      Repository repository,
      ObjectStore objects,
      ObjectMapper mapper,
      Duration expiry,
      Clock clock) {
    this.repository = require(repository, "operations repository");
    this.objects = require(objects, "object store");
    this.mapper = require(mapper, "object mapper");
    this.expiry =
        expiry == null || expiry.isZero() || expiry.isNegative() ? Duration.ofMinutes(10) : expiry;
    this.clock = require(clock, "clock");
  }

  @Override
  public EvidenceSource source(String evidenceId) {
    EvidenceSourceRef ref = one(evidenceId);
    ObjectInfo info = objects.stat(ref.objectKey());
    Instant expiresAt = clock.instant().plus(expiry);
    return new EvidenceSource(
        ref.evidenceId(),
        ref.documentId(),
        ref.versionId(),
        ref.objectKey(),
        info.contentType(),
        info.size(),
        ref.location(),
        "/api/enterprise/evidence/source/file?id=" + ref.evidenceId(),
        objects.presignGet(ref.objectKey(), expiry),
        expiresAt);
  }

  @Override
  public StoredObject openOriginal(String evidenceId) {
    EvidenceSourceRef ref = one(evidenceId);
    return objects.get(ref.objectKey());
  }

  @Override
  public PublishedPackage packageSources(List<String> evidenceIds) {
    List<String> requested =
        evidenceIds == null
            ? List.of()
            : evidenceIds.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    if (requested.isEmpty())
      throw new IllegalArgumentException("at least one evidence ID is required");
    List<EvidenceSourceRef> refs = repository.evidenceSources(requested);
    if (refs.size() != requested.size())
      throw new IllegalStateException("one or more evidence IDs cannot be resolved");

    Map<String, VersionGroup> byVersion = new LinkedHashMap<>();
    for (EvidenceSourceRef ref : refs) {
      VersionGroup group = byVersion.computeIfAbsent(ref.versionId(), key -> new VersionGroup(ref));
      group.evidenceIds.add(ref.evidenceId());
    }
    List<PackageVersion> versions = new ArrayList<>();
    for (VersionGroup group : byVersion.values()) {
      String archivePath = archivePath(group.ref);
      versions.add(
          new PackageVersion(
              group.ref.versionId(),
              group.ref.documentId(),
              group.ref.originalFilename(),
              group.ref.objectKey(),
              archivePath,
              List.copyOf(group.evidenceIds)));
    }
    Instant generatedAt = clock.instant();
    PackageManifest manifest = new PackageManifest(generatedAt, requested, versions);
    String packageId = UUID.randomUUID().toString();
    String objectKey = "packages/" + packageId + ".zip";
    Path temp = null;
    try {
      temp = Files.createTempFile("ekb-package-", ".zip");
      try (ZipOutputStream zip =
          new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
        for (PackageVersion version : versions) {
          zip.putNextEntry(new ZipEntry(version.archivePath()));
          StoredObject stored = objects.get(version.objectKey());
          try (InputStream input = stored.stream()) {
            input.transferTo(zip);
          }
          zip.closeEntry();
        }
        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write(mapper.writeValueAsBytes(manifest));
        zip.closeEntry();
      }
      long size = Files.size(temp);
      try (InputStream input = Files.newInputStream(temp)) {
        objects.put(objectKey, input, size, "application/zip");
      }
    } catch (Exception e) {
      throw new IllegalStateException("source package generation failed", e);
    } finally {
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (Exception ignored) {
        }
    }
    Instant expiresAt = generatedAt.plus(expiry);
    return new PublishedPackage(
        packageId, objectKey, objects.presignGet(objectKey, expiry), expiresAt, manifest);
  }

  private EvidenceSourceRef one(String evidenceId) {
    if (evidenceId == null || evidenceId.isBlank())
      throw new IllegalArgumentException("evidence ID is required");
    List<EvidenceSourceRef> refs = repository.evidenceSources(List.of(evidenceId.trim()));
    if (refs.size() != 1) throw new IllegalStateException("evidence unavailable: " + evidenceId);
    return refs.get(0);
  }

  private static String archivePath(EvidenceSourceRef ref) {
    String filename = safeFilename(ref.originalFilename());
    return "sources/"
        + safeSegment(ref.documentId())
        + "/"
        + safeSegment(ref.versionId())
        + "/"
        + filename;
  }

  private static String safeFilename(String value) {
    String normalized = value == null ? "source.bin" : value.replace('\\', '/');
    int slash = normalized.lastIndexOf('/');
    if (slash >= 0) normalized = normalized.substring(slash + 1);
    normalized = normalized.replaceAll("[\\r\\n\\x00-\\x1f]", "_").trim();
    return normalized.isEmpty() ? "source.bin" : normalized;
  }

  private static String safeSegment(String value) {
    String v = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    return v.isEmpty() ? "unknown" : v;
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " is required");
    return value;
  }

  private static final class VersionGroup {
    final EvidenceSourceRef ref;
    final LinkedHashSet<String> evidenceIds = new LinkedHashSet<>();

    VersionGroup(EvidenceSourceRef ref) {
      this.ref = ref;
    }
  }
}
