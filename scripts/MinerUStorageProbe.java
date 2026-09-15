package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.infrastructure.parser.MinerUPipelineAdapter;
import cn.lgs.knowledgebase.infrastructure.parser.JacksonParsedArtifactEncoder;
import cn.lgs.knowledgebase.infrastructure.raw.LocalEmbeddingTokenizer;
import cn.lgs.knowledgebase.infrastructure.storage.MinioObjectStoreAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Actual MinIO -> native MinerU Office -> MinIO artifacts -> native structural tokenizer. */
public final class MinerUStorageProbe {
  public static void main(String[] args) throws Exception {
    ObjectMapper json=new ObjectMapper();Map<String,Object> checks=new LinkedHashMap<>();Map<String,Object> result=new LinkedHashMap<>();
    String run=UUID.randomUUID().toString();
    Path progress=Path.of(args[2]+".progress.log");Files.writeString(progress,"Construct MinIO adapter\n");
    ObjectStore store=new MinioObjectStoreAdapter("http://127.0.0.1:19400",System.getenv("PROBE_MINIO_USER"),System.getenv("PROBE_MINIO_PASSWORD"),"evokb-target");
    Files.writeString(progress,"Put original\n",StandardOpenOption.APPEND);
    Path fixture=Path.of(args[1]);byte[] original=Files.readAllBytes(fixture);String key="acceptance/raw-parser/"+run+"/invoice-acceptance.docx";
    store.put(key,new java.io.ByteArrayInputStream(original),original.length,"application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    Files.writeString(progress,"Parse original from MinIO\n",StandardOpenOption.APPEND);
    var adapter=new MinerUPipelineAdapter("http://127.0.0.1:18486",json,Duration.ofMillis(50),Duration.ofSeconds(600),"auto","ch","hybrid-engine","medium","3.4.5",store);
    List<String> taskIds=new ArrayList<>();ParsedDocument parsed;
    try(var input=store.get(key).stream()) { parsed=adapter.parse("invoice-acceptance.docx",input,"",taskIds::add); }
    Files.writeString(progress,"Read parser image from MinIO\n",StandardOpenOption.APPEND);
    checks.put("actualMinioOriginalAndMinerU345Office",store.stat(key).size()==original.length&&parsed.parserName().equals("mineru-office")&&parsed.parserVersion().equals("3.4.5")&&taskIds.size()==1);
    checks.put("officeElementOrderNeverInventsPdfGeometry",parsed.blocks().stream().allMatch(block->block.page()==null&&block.bbox().isEmpty()&&block.pageSize().isEmpty()));
    var image=parsed.blocks().stream().filter(block->block.type().equals("image")).findFirst().orElseThrow();
    byte[] imageBytes;try(var stream=store.get(image.imagePath()).stream()) {imageBytes=stream.readAllBytes();}
    Files.writeString(progress,"Stat parser image\n",StandardOpenOption.APPEND);
    checks.put("imageStoredAndActuallyReadableFromMinio",image.imagePath().startsWith("parsed/mineru/"+taskIds.get(0)+"/images/")&&imageBytes.length>0&&store.stat(image.imagePath()).size()==imageBytes.length);
    byte[] artifact=new JacksonParsedArtifactEncoder(json).structuredArtifact(parsed);
    Files.writeString(progress,"Put structured artifact\n",StandardOpenOption.APPEND);
    String artifactKey="acceptance/raw-parser/"+run+"/structured.json";store.put(artifactKey,new java.io.ByteArrayInputStream(artifact),artifact.length,"application/json");
    byte[] reread;try(var stream=store.get(artifactKey).stream()) {reread=stream.readAllBytes();}
    Files.writeString(progress,"Construct native tokenizer\n",StandardOpenOption.APPEND);
    var structured=json.readTree(reread);var originalContent=json.readTree(parsed.contentList());
    checks.put("originalNativeJsonAndFrozenReceiptPersisted",Arrays.equals(artifact,reread)&&structured.path("content_list").equals(originalContent)&&structured.path("parser_receipt").path("modelRevision").asText().equals("native-office-mineru-3.4.5")&&!structured.path("parser_receipt").path("imageObjects").isEmpty());
    var manifest=json.readTree(Path.of(args[0]).resolve("manifest.json").toFile());
    try(var tokens=new LocalEmbeddingTokenizer(Path.of(args[0]).resolve("tokenizer.json"),manifest.path("tokenizerRevision").asText(),manifest.path("files").path("tokenizer.json").asText(),manifest.path("rendering").path("prefix").asText(),manifest.path("rendering").path("suffix").asText())) {
      Files.writeString(progress,"Build raw blocks\n",StandardOpenOption.APPEND);
      var blocks=RawKnowledgeBuilder.rawBlocks("probe-"+run,parsed.blocks(),tokens);
      Files.writeString(progress,"Build structural chunks\n",StandardOpenOption.APPEND);
      var chunks=RawKnowledgeBuilder.smartChunks("probe-doc-"+run,"probe-"+run,new Metadata("Synthetic","Synthetic",List.of(),"",null,"CONTRACT"),blocks,RawChunkProfile.DEFAULT,tokens);
      checks.put("actualOfficeOutputUsesActual900TokenChunkLimit",!chunks.isEmpty()&&chunks.stream().allMatch(chunk->chunk.tokenCount()==tokens.count(chunk.content())&&chunk.tokenCount()<=900));
      String body=chunks.stream().map(RawKnowledgeBuilder.ChunkDraft::content).reduce("",String::concat);
      checks.put("criticalFactsTableUnitsConditionsAndSpansSurvive",List.of("900万元","24个月","60日","HT-902","120.50","rowspan","colspan","单位：万元").stream().allMatch(body::contains));
      result.put("rawBlocks",blocks);result.put("chunks",chunks);result.put("tokenizerIdentity",tokens.identity());
      Files.writeString(progress,"Native tokenizer and structure complete\n",StandardOpenOption.APPEND);
    }
    result.put("checks",checks);result.put("status",checks.values().stream().allMatch(Boolean.TRUE::equals)?"PASS_ACTUAL_MINERU_MINIO_STRUCTURE":"FAIL_ACTUAL_MINERU_MINIO_STRUCTURE");result.put("parserTask",taskIds.get(0));result.put("originalObject",key);result.put("structuredObject",artifactKey);result.put("imageObject",image.imagePath());result.put("boundary","Actual MinIO original/result/image, native MinerU Office and actual tokenizer. No embeddings, database publication, browser or hybrid PDF success claimed.");
    json.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(),result);
    // This standalone probe owns the JVM. MinIO's idle OkHttp dispatcher is non-daemon;
    // finish only after all synchronous writes, readbacks and the immutable report complete.
    System.exit(checks.values().stream().allMatch(Boolean.TRUE::equals)?0:1);
  }
}
