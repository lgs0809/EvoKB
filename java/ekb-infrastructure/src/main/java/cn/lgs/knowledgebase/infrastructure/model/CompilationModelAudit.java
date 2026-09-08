package cn.lgs.knowledgebase.infrastructure.model;

import cn.lgs.knowledgebase.application.compilation.CompilationExecutionBudget;
import cn.lgs.knowledgebase.application.port.Ports.AuditEvent;
import cn.lgs.knowledgebase.application.port.Ports.AuditPort;
import cn.lgs.knowledgebase.application.port.Ports.ModelRole;
import cn.lgs.knowledgebase.application.port.Ports.ModelTask;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Preserve received text before decoding/semantic rejection, under its original task authority. */
public final class CompilationModelAudit {
  public static final int MAX_RESPONSE_BYTES = 65_536;
  private final AuditPort audit;

  public CompilationModelAudit(AuditPort audit) {
    this.audit = java.util.Objects.requireNonNull(audit);
  }

  public void received(
      ModelRole role, ModelTask modelTask, String model, Class<?> outputType, String response) {
    if (CompilationExecutionBudget.execution().isEmpty()) return;
    var details = new LinkedHashMap<String, Object>();
    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
    int start = 0, end = Math.min(response.length(), MAX_RESPONSE_BYTES);
    while (start < end) {
      int middle = (start + end + 1) / 2;
      if (response.substring(0, middle).getBytes(StandardCharsets.UTF_8).length
          <= MAX_RESPONSE_BYTES) start = middle;
      else end = middle - 1;
    }
    end = start;
    if (end > 0 && Character.isHighSurrogate(response.charAt(end - 1))) end--;
    details.put("responseText", response.substring(0, end));
    details.put("responseBytes", bytes.length);
    details.put("responseSha256", KnowledgeDomain.sha256(response));
    details.put("responseTruncated", end != response.length());
    details.put("outputType", outputType.getName());
    record(role, modelTask, model, "COMPILATION_MODEL_OUTPUT", "SUCCESS", details);
  }

  public void failed(
      ModelRole role,
      ModelTask modelTask,
      String model,
      Class<?> outputType,
      RuntimeException failure) {
    // Exception messages/provider bodies may include endpoint/account details; retain only type.
    record(
        role,
        modelTask,
        model,
        "COMPILATION_MODEL_FAILURE",
        "FAILURE",
        Map.of("outputType", outputType.getName(), "failureType", failure.getClass().getName()));
  }

  private void record(
      ModelRole role,
      ModelTask modelTask,
      String model,
      String action,
      String outcome,
      Map<String, Object> content) {
    CompilationExecutionBudget.execution()
        .ifPresent(
            task -> {
              var details = new LinkedHashMap<String, Object>(content);
              details.put("receiptVersion", "compilation-model-receipt-v1");
              details.put("taskId", task.taskId());
              details.put("attempt", task.attempt());
              details.put("executionEpoch", task.executionEpoch());
              details.put("sourceVersionId", task.versionId());
              details.put("compilationType", task.taskType().name());
              details.put("modelTask", modelTask.name());
              details.put("modelRole", role.name());
              details.put("model", model);
              details.put(
                  "phase",
                  action.equals("COMPILATION_MODEL_OUTPUT")
                      ? "RECEIVED_BEFORE_SCHEMA_AND_SEMANTIC_VALIDATION"
                      : "NO_USABLE_RESPONSE");
              audit.record(
                  new AuditEvent(
                      "",
                      "compilation-worker",
                      "SYSTEM",
                      action,
                      "compile_task",
                      task.taskId(),
                      "",
                      outcome,
                      UUID.randomUUID().toString(),
                      "",
                      "",
                      details,
                      Instant.now()));
            });
  }
}
