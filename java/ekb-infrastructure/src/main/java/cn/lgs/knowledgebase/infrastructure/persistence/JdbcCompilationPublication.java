package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.compilation.CompilationPublication;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Pin all sources before generation; hold source locks only during the final database commit. */
final class JdbcCompilationPublication implements CompilationPublication {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Runnable claimGuard;
  private final GenerationTask task;
  private final String namespace;
  private Map<String, Long> inputs;

  JdbcCompilationPublication(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      Runnable claimGuard,
      GenerationTask task) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transactions = Objects.requireNonNull(transactions);
    this.claimGuard = Objects.requireNonNull(claimGuard);
    this.task = task;
    namespace =
        task == null
            ? "compilation/" + UUID.randomUUID() + "/"
            : "compilation/" + task.taskId() + "/epoch-" + task.executionEpoch() + "/";
  }

  @Override
  public void captureInputs(List<String> versions) {
    if (versions == null
        || versions.isEmpty()
        || versions.stream().anyMatch(v -> v == null || v.isBlank()))
      throw new IllegalArgumentException("complete compilation inputs are required");
    var ids = versions.stream().distinct().sorted().toList();
    if (inputs != null) {
      if (!ids.equals(inputs.keySet().stream().sorted().toList()))
        throw new IllegalStateException("compilation input snapshot is already fixed");
      return;
    }
    inputs =
        transactions.execute(
            ignored -> {
              claimGuard.run();
              var snapshot = readInputs(ids);
              if (task != null) {
                var prior =
                    jdbc.queryForList(
                        "SELECT source_version_id,acl_version FROM compile_task_input "
                            + "WHERE task_id=? AND execution_epoch=? ORDER BY source_version_id",
                        task.taskId(),
                        task.executionEpoch());
                if (!prior.isEmpty()) {
                  if (prior.size() != snapshot.size()
                      || prior.stream()
                          .anyMatch(
                              row ->
                                  !Objects.equals(
                                      snapshot.get(row.get("source_version_id")),
                                      ((Number) row.get("acl_version")).longValue())))
                    throw new IllegalStateException(
                        "persisted compilation input snapshot cannot change");
                } else {
                  snapshot.forEach(
                      (id, acl) ->
                          jdbc.update(
                              "INSERT INTO compile_task_input(task_id,execution_epoch,source_version_id,acl_version) VALUES(?,?,?,?)",
                              task.taskId(),
                              task.executionEpoch(),
                              id,
                              acl));
                }
              }
              return Map.copyOf(snapshot);
            });
  }

  private Map<String, Long> readInputs(List<String> ids) {
    var snapshot = new java.util.LinkedHashMap<String, Long>();
    jdbc.query(
        "SELECT a.version_id,a.acl_version FROM kb_source_authority a "
            + "JOIN document_version v ON v.version_id=a.version_id JOIN document d ON d.document_id=a.document_id "
            + "JOIN document_metadata m ON m.version_id=a.version_id WHERE a.version_id IN ("
            + String.join(",", java.util.Collections.nCopies(ids.size(), "?"))
            + ") ORDER BY a.version_id FOR SHARE OF v,d,m",
        rs -> {
          snapshot.put(rs.getString(1), rs.getLong(2));
        },
        ids.toArray());
    if (snapshot.size() != ids.size())
      throw new IllegalStateException("compilation inputs are stale or missing");
    return snapshot;
  }

  private void verifyInputs() {
    if (inputs == null) throw new IllegalStateException("compilation inputs have not been fixed");
    if (!inputs.equals(readInputs(inputs.keySet().stream().sorted().toList())))
      throw new IllegalStateException(
          "compilation input permission changed after generation started");
  }

  @Override
  public void check() {
    transactions.executeWithoutResult(
        ignored -> {
          claimGuard.run();
          verifyInputs();
        });
  }

  @Override
  public void publish(Runnable writes) {
    transactions.executeWithoutResult(
        ignored -> {
          claimGuard.run();
          verifyInputs();
          writes.run();
          claimGuard.run();
          verifyInputs();
        });
  }

  @Override
  public String objectKey(String key) {
    if (key == null || key.isBlank() || key.startsWith("/") || key.contains(".."))
      throw new IllegalArgumentException("relative compilation object key required");
    return namespace + key;
  }
}
