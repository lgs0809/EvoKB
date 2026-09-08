package cn.lgs.knowledgebase.application.raw;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RawParseCheckpointTest {
  private static final String BODY = "付款需发票且验收；币种CNY，截止2026-10-07。";

  private static String sha(String value) {
    return ParserPipelineIdentity.hash(value.getBytes(StandardCharsets.UTF_8));
  }

  private static ParserPipelineIdentity identity(String source) {
    return new ParserPipelineIdentity(
        Map.of(
            "parser.name",
            "fixture-native",
            "parser.version",
            "1",
            "parser.format",
            "pdf",
            "runtime.sha256",
            sha(source)));
  }

  private static RawBlock block(String body) {
    return new RawBlock(
        "block-a",
        "version-a",
        0,
        "text",
        body,
        body,
        List.of("付款"),
        1,
        1,
        List.of(0d, 0d, 100d, 100d),
        List.of(600d, 800d),
        List.of("block-a"),
        sha(body),
        12);
  }

  private static RawParseCheckpoint checkpoint() {
    return new RawParseCheckpoint(
        identity("current source"),
        sha("original bytes"),
        14,
        sha("markdown bytes"),
        sha("structured bytes"),
        RawParseCheckpoint.blocksSha256(List.of(block(BODY))));
  }

  @Test
  void actualCurrentSourceAndCompleteBlockSetCanResume() {
    assertTrue(
        checkpoint()
            .matchesSourceAndBlocks(
                identity("current source"), sha("original bytes"), 14, List.of(block(BODY))));
  }

  @Test
  void sameVersionButChangedRuntimeCannotReuseParsedCheckpoint() {
    assertFalse(
        checkpoint()
            .matchesSourceAndBlocks(
                identity("changed source"), sha("original bytes"), 14, List.of(block(BODY))));
  }

  @Test
  void changedOriginalOrMissingBlockCannotAuthenticate() {
    assertFalse(
        checkpoint()
            .matchesSourceAndBlocks(
                identity("current source"), sha("other bytes"), 14, List.of(block(BODY))));
    assertFalse(
        checkpoint()
            .matchesSourceAndBlocks(
                identity("current source"), sha("original bytes"), 15, List.of(block(BODY))));
    assertFalse(
        checkpoint()
            .matchesSourceAndBlocks(
                identity("current source"), sha("original bytes"), 14, List.of()));
  }

  @Test
  void alteredConditionOrDuplicateBlockCannotAuthenticate() {
    assertFalse(
        checkpoint()
            .matchesSourceAndBlocks(
                identity("current source"),
                sha("original bytes"),
                14,
                List.of(block(BODY.replace("且", "或")))));
    assertFalse(
        checkpoint()
            .matchesSourceAndBlocks(
                identity("current source"),
                sha("original bytes"),
                14,
                List.of(block(BODY), block(BODY))));
  }

  @Test
  void componentOrderCannotChangeActualIdentity() {
    var a = identity("current source");
    assertEquals(
        a.sha256(),
        new ParserPipelineIdentity(new java.util.LinkedHashMap<>(a.components())).sha256());
    assertThrows(UnsupportedOperationException.class, () -> a.components().put("x", "y"));
  }

  @Test
  void missingOrForgedRuntimeIdentityIsNotFallbackCertified() {
    assertThrows(IllegalArgumentException.class, () -> new ParserPipelineIdentity(Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ParserPipelineIdentity(
                Map.of(
                    "parser.name",
                    "native",
                    "parser.version",
                    "1",
                    "parser.format",
                    "pdf",
                    "runtime.sha256",
                    "legacy")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RawParseCheckpoint(
                identity("current"), "unknown", 14, sha("md"), sha("json"), sha("blocks")));
  }

  @Test
  void extraRealPolicyAndDependencyChangesAreDifferentIdentities() {
    assertNotEquals(
        identity("current").sha256(),
        identity("current").with("native.table.policy", "actual-native-policy").sha256());
    assertNotEquals(
        identity("current").with("native.table.dependency", "0.11.9").sha256(),
        identity("current").with("native.table.dependency", "other-real-version").sha256());
  }

  @Test
  void everyBlockMustBindTheSameCompleteReceipt() {
    var original =
        new cn.lgs.knowledgebase.application.port.Ports.SourceFile(
            "fixture.pdf", "fixture.pdf", 14, sha("original bytes"), "application/pdf");
    var pinned = identity("current");
    var receipt =
        Map.<String, Object>of(
            "pipeline",
            Map.of("components", pinned.components(), "sha256", pinned.sha256()),
            "sourceSha256",
            original.sha256());
    var valid = parsedBlock(receipt);
    assertDoesNotThrow(
        () ->
            ParserCheckpointPolicy.requireParsed(parsed(List.of(valid, valid)), original, pinned));
    assertThrows(
        IllegalStateException.class,
        () ->
            ParserCheckpointPolicy.requireParsed(
                parsed(List.of(valid, parsedBlock(Map.of()))), original, pinned));
    var wrong = new java.util.LinkedHashMap<>(receipt);
    wrong.put("sourceSha256", sha("another original"));
    assertThrows(
        IllegalStateException.class,
        () ->
            ParserCheckpointPolicy.requireParsed(
                parsed(List.of(valid, parsedBlock(wrong))), original, pinned));
  }

  @Test
  void partialOrEmptyOutputCannotMasqueradeAsCompletedCurrentParse() {
    var original =
        new cn.lgs.knowledgebase.application.port.Ports.SourceFile(
            "fixture.pdf", "fixture.pdf", 14, sha("original bytes"), "application/pdf");
    assertThrows(
        IllegalStateException.class,
        () ->
            ParserCheckpointPolicy.requireParsed(parsed(List.of()), original, identity("current")));
    var forged =
        Map.<String, Object>of(
            "pipeline",
            Map.of("components", identity("current").components(), "sha256", sha("forged")),
            "sourceSha256",
            original.sha256());
    assertThrows(
        IllegalStateException.class,
        () ->
            ParserCheckpointPolicy.requireParsed(
                parsed(List.of(parsedBlock(forged))), original, identity("current")));
  }

  @Test
  void utf8ComponentFramesPreserveUnicodeWithoutAmbiguousConcatenation() {
    var a = identity("current").with("condition", "发票且验收");
    var b = identity("current").with("condition", "发票或验收");
    assertNotEquals(a.sha256(), b.sha256());
    var framed = new StringBuilder();
    ParserPipelineIdentity.append(framed, "验收");
    assertEquals("6:验收", framed.toString());
  }

  @Test
  void actualNestedImplementationBytesArePartOfTheDeclaration() {
    assertEquals(
        ParserPipelineIdentity.classSha256(RawParseCheckpointTest.class),
        ParserPipelineIdentity.classSha256(RawParseCheckpointTest.class));
    assertNotEquals(
        ParserPipelineIdentity.classSha256(RawParseCheckpoint.class),
        ParserPipelineIdentity.classSha256(RawKnowledgeBuilder.class));
  }

  @Test
  void unavailableActualParserIsNotSilentLegacyFallback() {
    cn.lgs.knowledgebase.application.port.Ports.ParserPort unavailable =
        (filename, source) -> {
          throw new AssertionError("identity observation must not parse");
        };
    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                ParserCheckpointPolicy.current(
                    unavailable,
                    "fixture.pdf",
                    RawChunkProfile.DEFAULT,
                    RawTestFixtures.TOKENS,
                    parsed -> new byte[0]));
    assertTrue(failure.getMessage().startsWith("PARSER_IDENTITY_UNAVAILABLE"));
  }

  @Test
  void retainedObjectsAndExactSourceVersionMustMatchTheWholeProof() {
    var proof = checkpoint();
    var original =
        new cn.lgs.knowledgebase.application.port.Ports.SourceFile(
            "fixture.pdf", "fixture.pdf", 14, sha("original bytes"), "application/pdf");
    var version =
        new cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.DocumentVersion(
            "version-a",
            "doc-a",
            1,
            cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.VersionLifecycle.FAILED,
            "fixture.pdf",
            "application/pdf",
            14,
            original.sha256(),
            "original",
            "markdown",
            "structured",
            "fixture-native",
            "1");
    var bytes =
        Map.of(
            "markdown",
            "markdown bytes".getBytes(StandardCharsets.UTF_8),
            "structured",
            "structured bytes".getBytes(StandardCharsets.UTF_8));
    var objects = objectFixture(bytes);
    assertTrue(
        ParserCheckpointPolicy.reusable(
            proof, version, original, identity("current source"), List.of(block(BODY)), objects));
    var changed = new java.util.LinkedHashMap<>(bytes);
    changed.put("structured", "missing a condition".getBytes(StandardCharsets.UTF_8));
    assertFalse(
        ParserCheckpointPolicy.reusable(
            proof,
            version,
            original,
            identity("current source"),
            List.of(block(BODY)),
            objectFixture(changed)));
    var another =
        new cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.DocumentVersion(
            "other-version",
            "doc-a",
            1,
            version.lifecycle(),
            version.originalFilename(),
            version.mediaType(),
            14,
            original.sha256(),
            "original",
            "markdown",
            "structured",
            "fixture-native",
            "1");
    assertFalse(
        ParserCheckpointPolicy.reusable(
            proof, another, original, identity("current source"), List.of(block(BODY)), objects));
  }

  private static cn.lgs.knowledgebase.application.port.Ports.ObjectStore objectFixture(
      Map<String, byte[]> bytes) {
    return (cn.lgs.knowledgebase.application.port.Ports.ObjectStore)
        java.lang.reflect.Proxy.newProxyInstance(
            RawParseCheckpointTest.class.getClassLoader(),
            new Class<?>[] {cn.lgs.knowledgebase.application.port.Ports.ObjectStore.class},
            (proxy, method, args) -> {
              if (!method.getName().equals("get"))
                throw new AssertionError("checkpoint verification may only read retained objects");
              byte[] value = bytes.get(args[0]);
              return new cn.lgs.knowledgebase.application.port.Ports.StoredObject(
                  new java.io.ByteArrayInputStream(value), value.length, "component");
            });
  }

  private static cn.lgs.knowledgebase.application.port.Ports.ParsedBlock parsedBlock(
      Map<String, Object> receipt) {
    return new cn.lgs.knowledgebase.application.port.Ports.ParsedBlock(
        "text",
        BODY,
        0,
        0,
        List.of(),
        List.of(),
        "",
        "",
        "",
        Map.of("_evokb_parser_receipt", receipt));
  }

  private static cn.lgs.knowledgebase.application.port.Ports.ParsedDocument parsed(
      List<cn.lgs.knowledgebase.application.port.Ports.ParsedBlock> blocks) {
    return new cn.lgs.knowledgebase.application.port.Ports.ParsedDocument(
        BODY, "{}", "[]", "fixture-native", "1", blocks);
  }
}
