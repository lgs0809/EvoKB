package cn.lgs.knowledgebase.application.compilation;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.CompilationRepository;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;

/** Frozen Summary revision and repair cause carried by a durable Concept task identity. */
public record ConceptTaskBinding(
    String summaryId, long aclVersion, String contentHash, String repairCause) {
  public ConceptTaskBinding {
    if (summaryId == null
        || summaryId.isBlank()
        || summaryId.contains("/")
        || aclVersion < 1
        || contentHash == null
        || contentHash.isBlank()
        || contentHash.contains("/")
        || repairCause == null
        || (!repairCause.isEmpty() && !repairCause.matches("[0-9a-f]{64}")))
      throw new IllegalArgumentException("Invalid Concept input binding");
  }

  public static ConceptTaskBinding from(
      CompilationContracts.SummaryRecord summary, GenerationTask parent) {
    return new ConceptTaskBinding(
        summary.id(),
        parent.sourceAclVersion(),
        summary.contentHash(),
        parent.subjectId().startsWith("repair/") ? KnowledgeDomain.sha256(parent.subjectId()) : "");
  }

  public String subject() {
    return "summary/"
        + summaryId
        + "/acl/"
        + aclVersion
        + "/content/"
        + contentHash
        + (repairCause.isEmpty() ? "" : "/repair/" + repairCause);
  }

  public static String verifiedSummaryId(GenerationTask task, CompilationRepository repository) {
    // Legacy durable tasks keep their original identity and validation behavior.
    if (!task.subjectId().startsWith("summary/")) return task.subjectId();
    String[] parts = task.subjectId().split("/", -1);
    if ((parts.length != 6 && parts.length != 8)
        || !parts[2].equals("acl")
        || !parts[4].equals("content")
        || (parts.length == 8 && !parts[6].equals("repair")))
      throw new IllegalStateException("Concept source revision binding is invalid");
    final ConceptTaskBinding binding;
    try {
      binding =
          new ConceptTaskBinding(
              parts[1], Long.parseLong(parts[3]), parts[5], parts.length == 8 ? parts[7] : "");
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException("Concept source revision binding is invalid", malformed);
    }
    if (binding.aclVersion() != task.sourceAclVersion())
      throw new IllegalStateException("Concept source permission changed after queueing");
    var summary =
        repository
            .findSummary(binding.summaryId())
            .orElseThrow(() -> new IllegalStateException("Concept source summary disappeared"));
    if (!summary.versionId().equals(task.versionId())
        || !summary.contentHash().equals(binding.contentHash()))
      throw new IllegalStateException("Concept source summary changed after queueing");
    return binding.summaryId();
  }
}
