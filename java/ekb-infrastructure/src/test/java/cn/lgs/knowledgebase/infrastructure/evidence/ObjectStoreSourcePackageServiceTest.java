package cn.lgs.knowledgebase.infrastructure.evidence;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.operations.EnterpriseOperations.EvidenceSourceRef;
import cn.lgs.knowledgebase.application.operations.EnterpriseOperations.Repository;
import cn.lgs.knowledgebase.application.port.Ports.ObjectInfo;
import cn.lgs.knowledgebase.application.port.Ports.ObjectStore;
import cn.lgs.knowledgebase.application.port.Ports.SourcePackageGroup;
import cn.lgs.knowledgebase.application.port.Ports.StoredObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class ObjectStoreSourcePackageServiceTest {
  @Test
  void singleQueryPackageUsesRootFilenamesAndReadableDuplicateSuffix() throws Exception {
    var refs =
        Map.of(
            "e1", ref("e1", "d1", "v1", "制度.pdf", "original/d1/v1/a.pdf"),
            "e2", ref("e2", "d2", "v2", "制度.pdf", "original/d2/v2/b.pdf"));
    var objects = new MemoryObjectStore();
    objects.seed("original/d1/v1/a.pdf", "A");
    objects.seed("original/d2/v2/b.pdf", "B");
    var service = service(refs, objects);

    var published = service.packageSources(List.of("e1", "e2"));
    var entries = zipEntries(objects.bytes(published.objectKey()));

    assertEquals(List.of("制度.pdf", "制度 (2).pdf", "manifest.json"), entries);
    assertEquals("制度.pdf", published.manifest().versions().get(0).archivePath());
    assertEquals("制度 (2).pdf", published.manifest().versions().get(1).archivePath());
    assertTrue(
        published.manifest().versions().stream()
            .allMatch(v -> !v.archivePath().contains("sources/")));
    assertTrue(
        published.manifest().versions().stream()
            .allMatch(v -> !v.archivePath().contains(v.documentId())));
    assertUtf8FlagPresent(objects.bytes(published.objectKey()));
  }

  @Test
  void groupedPackageUsesReadableQueryFoldersAndUtf8Names() throws Exception {
    var refs =
        Map.of(
            "e1", ref("e1", "d1", "v1", "制度.pdf", "original/d1/v1/a.pdf"),
            "e2", ref("e2", "d2", "v2", "制度.pdf", "original/d2/v2/b.pdf"),
            "e3", ref("e3", "d3", "v3", "制度.pdf", "original/d3/v3/c.pdf"));
    var objects = new MemoryObjectStore();
    objects.seed("original/d1/v1/a.pdf", "A");
    objects.seed("original/d2/v2/b.pdf", "B");
    objects.seed("original/d3/v3/c.pdf", "C");
    var service = service(refs, objects);

    var published =
        service.packageGroupedSources(
            List.of(
                new SourcePackageGroup("万科董事会委员会", List.of("e1", "e2")),
                new SourcePackageGroup("万科董事会委员会", List.of("e3"))));
    var entries = zipEntries(objects.bytes(published.objectKey()));

    assertEquals(
        List.of("万科董事会委员会/制度.pdf", "万科董事会委员会/制度 (2).pdf", "万科董事会委员会 (2)/制度.pdf", "manifest.json"),
        entries);
    assertTrue(entries.stream().noneMatch(name -> name.contains("/sources/")));
    assertTrue(entries.stream().noneMatch(name -> name.contains("d1") || name.contains("v1")));
    assertEquals(List.of("e1", "e2", "e3"), published.manifest().evidenceIds());
    assertEquals("d1", published.manifest().versions().get(0).documentId());
    assertEquals("v1", published.manifest().versions().get(0).versionId());
    assertUtf8FlagPresent(objects.bytes(published.objectKey()));
  }

  private static ObjectStoreSourcePackageService service(
      Map<String, EvidenceSourceRef> refs, MemoryObjectStore objects) {
    Repository repository =
        (Repository)
            Proxy.newProxyInstance(
                Repository.class.getClassLoader(),
                new Class<?>[] {Repository.class},
                (proxy, method, args) -> {
                  if ("evidenceSources".equals(method.getName())) {
                    @SuppressWarnings("unchecked")
                    List<String> ids = (List<String>) args[0];
                    return ids.stream().map(refs::get).filter(java.util.Objects::nonNull).toList();
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    return new ObjectStoreSourcePackageService(
        repository, objects, new ObjectMapper().findAndRegisterModules(), Duration.ofMinutes(10));
  }

  private static EvidenceSourceRef ref(
      String evidenceId, String documentId, String versionId, String filename, String objectKey) {
    return new EvidenceSourceRef(
        evidenceId, documentId, versionId, filename, objectKey, Map.of("page_start", 1));
  }

  private static List<String> zipEntries(byte[] bytes) throws Exception {
    List<String> names = new ArrayList<>();
    try (ZipInputStream zip =
        new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        names.add(entry.getName());
        zip.closeEntry();
      }
    }
    return List.copyOf(names);
  }

  private static void assertUtf8FlagPresent(byte[] bytes) {
    boolean sawCentralDirectory = false;
    for (int i = 0; i + 10 < bytes.length; i++) {
      if ((bytes[i] & 0xff) == 0x50
          && (bytes[i + 1] & 0xff) == 0x4b
          && (bytes[i + 2] & 0xff) == 0x01
          && (bytes[i + 3] & 0xff) == 0x02) {
        sawCentralDirectory = true;
        int generalPurposeFlags = (bytes[i + 8] & 0xff) | ((bytes[i + 9] & 0xff) << 8);
        assertNotEquals(0, generalPurposeFlags & 0x0800, "ZIP entry must advertise UTF-8 names");
      }
    }
    assertTrue(sawCentralDirectory, "central directory not found");
  }

  private static final class MemoryObjectStore implements ObjectStore {
    private final Map<String, byte[]> data = new LinkedHashMap<>();
    private final Map<String, String> contentTypes = new LinkedHashMap<>();

    void seed(String key, String value) {
      data.put(key, value.getBytes(StandardCharsets.UTF_8));
      contentTypes.put(key, "application/pdf");
    }

    byte[] bytes(String key) {
      byte[] value = data.get(key);
      if (value == null) throw new IllegalStateException("missing object: " + key);
      return value.clone();
    }

    @Override
    public void put(String key, InputStream source, long size, String contentType) {
      try {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        source.transferTo(out);
        data.put(key, out.toByteArray());
        contentTypes.put(key, contentType);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public StoredObject get(String key) {
      byte[] value = bytes(key);
      return new StoredObject(
          new ByteArrayInputStream(value),
          value.length,
          contentTypes.getOrDefault(key, "application/octet-stream"));
    }

    @Override
    public ObjectInfo stat(String key) {
      byte[] value = bytes(key);
      return new ObjectInfo(
          key, value.length, contentTypes.getOrDefault(key, "application/octet-stream"), "etag");
    }

    @Override
    public String presignGet(String key, Duration expiry) {
      return "https://example.test/" + key;
    }

    @Override
    public void delete(String key) {
      data.remove(key);
      contentTypes.remove(key);
    }
  }
}
