import cn.lgs.knowledgebase.infrastructure.persistence.JdbcPublishedGraphReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only deployed-native graph readback. No duplicate policy SQL or model calls. */
public final class PublishedGraphReadbackProbe {
  public static void main(String[] args) throws Exception {
    var ds = new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),
        "evokb_target", System.getenv("PROBE_DB_PASSWORD"));
    var jdbc = new JdbcTemplate(ds);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    tx.setReadOnly(true);
    tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    var report = tx.execute(status -> {
      int clearance = jdbc.queryForObject(
          "SELECT security_clearance FROM enterprise_user WHERE user_id=? AND status='ACTIVE'",
          Integer.class, args[2]);
      var rows = new JdbcPublishedGraphReader(jdbc, args[1]).browse(args[3], 2, 100, null, null, clearance);
      var result = new ArrayList<Map<String,Object>>();
      for (var r : rows) {
        var m = new LinkedHashMap<String,Object>();
        m.put("relation_id", r.relationId()); m.put("source_entity_id", r.sourceEntityId());
        m.put("source_name", r.sourceName()); m.put("source_type", r.sourceType());
        m.put("target_entity_id", r.targetEntityId()); m.put("target_name", r.targetName());
        m.put("target_type", r.targetType()); m.put("relation_type", r.relationType());
        if(r.validFrom()!=null)m.put("valid_from",r.validFrom().toString());
        if(r.validTo()!=null)m.put("valid_to",r.validTo().toString());
        m.put("conflict_status", r.conflictStatus()); m.put("evidence_ids", r.evidenceIds());
        m.put("score", r.score()); result.add(m);
      }
      return Map.of("knowledgeBaseId", args[1], "userId", args[2], "securityClearance", clearance,
          "query", args[3], "depth", 2, "limit", 100, "relations", result);
    });
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), report);
  }
}
