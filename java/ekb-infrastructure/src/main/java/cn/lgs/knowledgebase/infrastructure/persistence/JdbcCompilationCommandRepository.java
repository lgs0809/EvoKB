package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.access.KnowledgeAccessService;
import cn.lgs.knowledgebase.application.compilation.CompilationCommands.*;
import cn.lgs.knowledgebase.application.compilation.CompilationPublication;
import cn.lgs.knowledgebase.application.port.Ports.UserRepository;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Native atomic admission and immutable command inputs, outside all model/object/index requests.
 */
public final class JdbcCompilationCommandRepository implements Store {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ObjectMapper mapper;
  private final JdbcGenerationTaskRepository tasks;
  private final UserRepository users;
  private final KnowledgeAccessService access;
  private final String knowledgeBase;

  public JdbcCompilationCommandRepository(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      ObjectMapper mapper,
      JdbcGenerationTaskRepository tasks,
      UserRepository users,
      KnowledgeAccessService access,
      String knowledgeBase) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.tx = Objects.requireNonNull(tx);
    this.mapper = Objects.requireNonNull(mapper);
    this.tasks = Objects.requireNonNull(tasks);
    this.users = Objects.requireNonNull(users);
    this.access = Objects.requireNonNull(access);
    if (knowledgeBase == null || knowledgeBase.isBlank())
      throw new IllegalArgumentException("trusted knowledge base is required");
    this.knowledgeBase = knowledgeBase;
  }

  public GenerationTask submit(String user, Request request) {
    Objects.requireNonNull(request);
    return tx.execute(
        ignored -> {
          requireAdmin(user);
          var versions = sourceVersions(request);
          access.requireVersions(user, versions);
          var inputs = capture(versions);
          String requestJson = json(request), inputsJson = json(inputs);
          String hash =
              KnowledgeDomain.sha256(
                  knowledgeBase + "\n" + user + "\n" + requestJson + "\n" + inputsJson);
          var task =
              tasks.queue(
                  versions.get(0), request.kind().taskType(), "command/" + hash, Instant.now());
          jdbc.update(
              "INSERT INTO kb_compilation_command(task_id,knowledge_base_id,submitted_by,request_json,input_versions,request_sha256) VALUES(?,?,?,?::jsonb,?::jsonb,?) ON CONFLICT DO NOTHING",
              task.taskId(),
              knowledgeBase,
              user,
              requestJson,
              inputsJson,
              hash);
          return task;
        });
  }

  private List<String> sourceVersions(Request request) {
    if (request.kind() == Kind.SUMMARY) return List.of(request.versionId());
    if (request.kind() == Kind.WIKI_KEYWORDS) {
      Boolean active =
          jdbc.queryForObject(
              "SELECT EXISTS(SELECT 1 FROM wiki WHERE wiki_id=? AND wiki_version=? AND lifecycle='ACTIVE')",
              Boolean.class,
              request.wikiId(),
              request.wikiVersion());
      if (!Boolean.TRUE.equals(active)) throw new KnowledgeAccessService.Denied();
      var versions =
          jdbc.query(
              "SELECT source_version_id FROM kb_asset_build_dependency WHERE asset_type='WIKI' AND asset_id=? AND build_revision=? ORDER BY source_version_id",
              (rs, n) -> rs.getString(1),
              request.wikiId(),
              Long.toString(request.wikiVersion()));
      if (versions.isEmpty()) throw new KnowledgeAccessService.Denied();
      return versions;
    }
    var versions = new TreeSet<String>();
    for (String summary : request.summaryIds()) {
      var found =
          jdbc.query(
              "SELECT version_id FROM summary WHERE summary_id=? AND lifecycle='ACTIVE' AND grounding_status='PASS' AND coverage_status='PASS'",
              (rs, n) -> rs.getString(1),
              summary);
      if (found.size() != 1) throw new KnowledgeAccessService.Denied();
      versions.add(found.get(0));
    }
    return List.copyOf(versions);
  }

  private record Input(String versionId, long aclVersion) {}

  private List<Input> capture(List<String> versions) {
    var inputs = new ArrayList<Input>();
    for (String id : versions) {
      var rows =
          jdbc.query(
              "SELECT version_id,acl_version FROM kb_source_authority WHERE version_id=?",
              (rs, n) -> new Input(rs.getString(1), rs.getLong(2)),
              id);
      if (rows.size() != 1) throw new KnowledgeAccessService.Denied();
      inputs.add(rows.get(0));
    }
    return List.copyOf(inputs);
  }

  private void requireAdmin(String user) {
    if (users.findById(user).filter(u -> u.active() && u.administrator()).isEmpty())
      throw new KnowledgeAccessService.Denied();
    // Also holds account authority through a fenced commit when called within its transaction.
    if (jdbc.queryForList(
                "SELECT user_id FROM enterprise_user WHERE user_id=? AND role='ADMIN' AND status='ACTIVE' FOR SHARE",
                user)
            .size()
        != 1) throw new KnowledgeAccessService.Denied();
  }

  public Optional<Command> find(GenerationTask task) {
    var rows =
        jdbc.query(
            "SELECT submitted_by,request_json::text,input_versions::text,request_sha256 FROM kb_compilation_command WHERE task_id=? AND knowledge_base_id=?",
            (rs, n) -> {
              Request request = decode(rs.getString(2), Request.class);
              List<Input> inputs = decodeInputs(rs.getString(3));
              String hash =
                  KnowledgeDomain.sha256(
                      knowledgeBase
                          + "\n"
                          + rs.getString(1)
                          + "\n"
                          + json(request)
                          + "\n"
                          + json(inputs));
              if (!hash.equals(rs.getString(4))
                  || !task.subjectId().equals("command/" + hash)
                  || request.kind().taskType() != task.taskType()
                  || !inputs.get(0).versionId().equals(task.versionId()))
                throw new IllegalStateException(
                    "compilation command differs from its durable identity");
              return new Command(
                  rs.getString(1), request, inputs.stream().map(Input::versionId).toList());
            },
            task.taskId(),
            knowledgeBase);
    if (task.subjectId().startsWith("command/") && rows.isEmpty())
      throw new IllegalStateException("durable compilation command is missing");
    return rows.stream().findFirst();
  }

  public CompilationPublication publication(
      GenerationTask task, Command command, CompilationPublication owned) {
    if (!find(task).orElseThrow().equals(command))
      throw new IllegalStateException("command differs from the immutable submission");
    var frozen =
        jdbc.queryForObject(
            "SELECT input_versions::text FROM kb_compilation_command WHERE task_id=? AND knowledge_base_id=?",
            String.class,
            task.taskId(),
            knowledgeBase);
    var inputs = decodeInputs(frozen);
    requireAdmin(command.submittedBy());
    access.requireVersions(command.submittedBy(), command.sourceVersions());
    if (!capture(command.sourceVersions()).equals(inputs))
      throw new IllegalStateException("compilation request inputs changed after submission");
    return new CompilationPublication() {
      private List<String> all = command.sourceVersions();

      private void authority() {
        requireAdmin(command.submittedBy());
        access.requireVersions(command.submittedBy(), all);
        if (!capture(command.sourceVersions()).equals(inputs))
          throw new IllegalStateException("compilation request inputs changed after submission");
      }

      public void captureInputs(List<String> ids) {
        var union = new TreeSet<>(command.sourceVersions());
        union.addAll(ids);
        all = List.copyOf(union);
        authority();
        owned.captureInputs(all);
      }

      public void check() {
        authority();
        owned.check();
      }

      public void publish(Runnable writes) {
        owned.publish(
            () -> {
              authority();
              writes.run();
              authority();
            });
      }

      public String objectKey(String key) {
        return owned.objectKey(key);
      }
    };
  }

  private List<Input> decodeInputs(String text) {
    try {
      return mapper.readValue(text, new TypeReference<List<Input>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("invalid stored compilation inputs", e);
    }
  }

  private <T> T decode(String text, Class<T> type) {
    try {
      return mapper.readValue(text, type);
    } catch (Exception e) {
      throw new IllegalStateException("invalid stored compilation command", e);
    }
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("cannot serialize compilation command", e);
    }
  }
}
