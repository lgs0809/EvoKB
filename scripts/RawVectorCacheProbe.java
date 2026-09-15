import cn.lgs.knowledgebase.infrastructure.raw.JdbcRawVectorCache;
import cn.lgs.knowledgebase.application.raw.RawEncodingProfile;
import cn.lgs.knowledgebase.application.raw.RawVectorEncoder;
import cn.lgs.knowledgebase.application.raw.RawVectorCache;
import cn.lgs.knowledgebase.application.raw.RawTokenCounter;
import cn.lgs.knowledgebase.application.raw.RawChunkProfile;
import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual PostgreSQL cache contract; synthetic vectors are explicitly not model inference. */
public final class RawVectorCacheProbe {
  public static void main(String[] args)throws Exception {
    var source=new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD"));
    JdbcTemplate jdbc=new JdbcTemplate(source);ObjectMapper json=new ObjectMapper();var cache=new JdbcRawVectorCache(jdbc,json);Map<String,Object> checks=new LinkedHashMap<>();
    List<Float> vector=java.util.stream.IntStream.range(0,1024).mapToObj(i->i/1024f).toList();
    RawEncodingProfile profile=new RawEncodingProfile("Qwen/Qwen3-VL-Embedding-2B","9f2f7e710d6d81056aa5c0a4f04764fec6bb7bda",1024,"probe-explicit-synthetic-tokenizer","probe-whole-input-v1","Represent the user's input.","probe-loopback-endpoint");
    String text="合同HT-900：发票且验收后60日，900万元。",input=KnowledgeDomain.sha256(text),key=profile.key("doc-public",text);
    cache.put(key,profile.fingerprint(),input,1024,vector);
    checks.put("postgresFinite1024VectorRoundTrip",cache.find(key,profile.fingerprint(),input,1024).orElseThrow().equals(vector));
    checks.put("allLookupIdentityPartsRequired",cache.find(key,KnowledgeDomain.sha256("otherprofile"),input,1024).isEmpty()&&cache.find(key,profile.fingerprint(),KnowledgeDomain.sha256("otherinput"),1024).isEmpty()&&cache.find(key,profile.fingerprint(),input,512).isEmpty());
    ExecutorService executor=Executors.newFixedThreadPool(4);List<Future<?>> writes=new ArrayList<>();
    for(int i=0;i<24;i++)writes.add(executor.submit(()->cache.put(key,profile.fingerprint(),input,1024,Collections.nCopies(1024,99f))));
    for(var write:writes)write.get(10,TimeUnit.SECONDS);executor.shutdown();
    checks.put("concurrentWritersCannotOverwriteImmutableWinner",cache.find(key,profile.fingerprint(),input,1024).orElseThrow().equals(vector)&&jdbc.queryForObject("SELECT COUNT(*) FROM raw_vector_cache",Integer.class)==1);
    boolean invalidRejected=false;try {cache.put(KnowledgeDomain.sha256("bad"),profile.fingerprint(),input,1024,Collections.nCopies(1024,Float.NaN));}catch(IllegalStateException expected){invalidRejected=true;}
    checks.put("nonfiniteVectorNeverStored",invalidRejected&&jdbc.queryForObject("SELECT COUNT(*) FROM raw_vector_cache",Integer.class)==1);
    String corrupt=KnowledgeDomain.sha256("corrupt-synthetic-fixture");jdbc.update("INSERT INTO raw_vector_cache(cache_key,profile_hash,input_hash,dimensions,vector_json) VALUES(?,?,?,?,?)",corrupt,profile.fingerprint(),input,1024,"[1]");
    boolean corruptRejected=false;try{cache.find(corrupt,profile.fingerprint(),input,1024);}catch(IllegalStateException expected){corruptRejected=true;}
    checks.put("malformedStoredVectorFailsClosed",corruptRejected);
    RawTokenCounter tokens=new RawTokenCounter(){public int count(String value){return value.length();}public String identity(){return "probe-explicit-synthetic-tokenizer";}};
    AtomicInteger calls=new AtomicInteger();EmbeddingPort fake=new EmbeddingPort(){public int dimensions(){return 1024;}public List<List<Float>> embed(List<String> inputs){calls.incrementAndGet();return inputs.stream().map(value->vector).toList();}};
    RawVectorEncoder encoder=new RawVectorEncoder(fake,()->profile,cache);var session=encoder.open("doc-public",tokens,RawChunkProfile.DEFAULT);session.encode(List.of(text));
    checks.put("actualPostgresHitSkipsEncodingOnly",calls.get()==0);
    session.encode(List.of(text.replace("60日","30日")));checks.put("changedBusinessInputReencodes",calls.get()==1);
    RawEncodingProfile changed=new RawEncodingProfile(profile.model(),"changed-revision",1024,profile.tokenizerIdentity(),profile.preprocessingIdentity(),profile.instruction(),profile.endpointIdentity());
    var changedSession=new RawVectorEncoder(fake,()->changed,cache).open("doc-public",tokens,RawChunkProfile.DEFAULT);changedSession.encode(List.of(text));
    checks.put("changedModelRevisionReencodes",calls.get()==2&&!session.checkpointStrategy().equals(changedSession.checkpointStrategy()));
    encoder.open("doc-secret",tokens,RawChunkProfile.DEFAULT).encode(List.of(text));checks.put("differentSourceDomainCannotShareCalculationEntry",calls.get()==3);
    Map<String,Object> report=new LinkedHashMap<>();report.put("checks",checks);report.put("status",checks.values().stream().allMatch(Boolean.TRUE::equals)?"PASS_ACTUAL_POSTGRES_RAW_CACHE":"FAIL_ACTUAL_POSTGRES_RAW_CACHE");report.put("rows",jdbc.queryForList("SELECT cache_key,profile_hash,input_hash,dimensions,created_at FROM raw_vector_cache ORDER BY created_at"));report.put("cacheKeys",Map.of("original",key,"corruptFixture",corrupt));report.put("boundary","Actual PostgreSQL concurrency/cache identity/storage. Explicit synthetic vectors/counter for protocol tests; no real embedding quality/inference or application publication claimed.");
    json.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);if(!checks.values().stream().allMatch(Boolean.TRUE::equals))System.exit(1);
  }
}
