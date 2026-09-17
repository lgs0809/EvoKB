package cn.lgs.knowledgebase.domain.query;

import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import java.time.Instant;
import java.util.List;

public final class QueryDomain {
  private QueryDomain() {}

  public enum SessionStatus {
    RUNNING,
    WAITING_USER,
    ANSWERED,
    INSUFFICIENT,
    FAILED
  }

  public enum TurnRole {
    USER,
    ASSISTANT,
    SYSTEM_EVENT
  }

  public enum Decision {
    ANSWER,
    CONTINUE_SEARCH,
    ASK_USER
  }

  public record QuerySession(
      String sessionId,
      String userId,
      String name,
      String originalQuestion,
      SearchFilters filters,
      SessionStatus status,
      String finalAnswer,
      boolean evidenceOnly,
      String clarificationQuestion,
      List<String> choices,
      List<String> citationEvidenceIds,
      Instant createdAt,
      Instant updatedAt) {
    public QuerySession {
      if (sessionId == null || sessionId.isBlank())
        throw new IllegalArgumentException("query session id is required");
      if (userId == null || userId.isBlank())
        throw new IllegalArgumentException("query session user id is required");
      if (originalQuestion == null || originalQuestion.isBlank())
        throw new IllegalArgumentException("original question is required");
      name = name == null || name.isBlank() ? originalQuestion.trim() : name.trim();
      filters = filters == null ? new SearchFilters(null, null, null, List.of()) : filters;
      status = status == null ? SessionStatus.RUNNING : status;
      finalAnswer = finalAnswer == null ? "" : finalAnswer;
      clarificationQuestion = clarificationQuestion == null ? "" : clarificationQuestion;
      choices = choices == null ? List.of() : List.copyOf(choices);
      citationEvidenceIds =
          citationEvidenceIds == null ? List.of() : List.copyOf(citationEvidenceIds);
      createdAt = createdAt == null ? Instant.now() : createdAt;
      updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public QuerySession withStatus(
        SessionStatus next,
        String answer,
        boolean nextEvidenceOnly,
        String clarification,
        List<String> nextChoices,
        List<String> citations,
        Instant now) {
      return new QuerySession(
          sessionId,
          userId,
          name,
          originalQuestion,
          filters,
          next,
          answer,
          nextEvidenceOnly,
          clarification,
          nextChoices,
          citations,
          createdAt,
          now);
    }
  }

  public record QueryGroup(
      String groupId,
      String userId,
      String name,
      List<String> sessionIds,
      Instant createdAt,
      Instant updatedAt) {
    public QueryGroup {
      if (groupId == null || groupId.isBlank())
        throw new IllegalArgumentException("query group id is required");
      if (userId == null || userId.isBlank())
        throw new IllegalArgumentException("query group user id is required");
      if (name == null || name.isBlank())
        throw new IllegalArgumentException("query group name is required");
      name = name.trim();
      sessionIds = sessionIds == null ? List.of() : List.copyOf(sessionIds);
      createdAt = createdAt == null ? Instant.now() : createdAt;
      updatedAt = updatedAt == null ? createdAt : updatedAt;
    }
  }

  public record QueryPackage(
      String packageId,
      String userId,
      String ownerType,
      String ownerId,
      String objectKey,
      String status,
      Instant deletedAt,
      Instant createdAt) {
    public QueryPackage {
      if (packageId == null || packageId.isBlank())
        throw new IllegalArgumentException("query package id is required");
      if (userId == null || userId.isBlank())
        throw new IllegalArgumentException("query package user id is required");
      if (!List.of("SESSION", "GROUP").contains(ownerType))
        throw new IllegalArgumentException("query package owner type is invalid");
      if (ownerId == null || ownerId.isBlank())
        throw new IllegalArgumentException("query package owner id is required");
      if (objectKey == null || objectKey.isBlank())
        throw new IllegalArgumentException("query package object key is required");
      status = status == null || status.isBlank() ? "AVAILABLE" : status.trim().toUpperCase();
      if (!List.of("AVAILABLE", "DELETED", "MISSING").contains(status))
        throw new IllegalArgumentException("query package status is invalid");
      createdAt = createdAt == null ? Instant.now() : createdAt;
    }
  }

  public record QueryTurn(
      String turnId, String sessionId, TurnRole role, String content, Instant createdAt) {
    public QueryTurn {
      if (turnId == null || turnId.isBlank())
        throw new IllegalArgumentException("query turn id is required");
      if (sessionId == null || sessionId.isBlank())
        throw new IllegalArgumentException("query turn session id is required");
      role = role == null ? TurnRole.SYSTEM_EVENT : role;
      content = content == null ? "" : content;
      createdAt = createdAt == null ? Instant.now() : createdAt;
    }
  }

  public record RetrievalRound(
      String roundId,
      String sessionId,
      int roundNo,
      List<String> inputQueries,
      Decision decision,
      List<String> missingInformation,
      List<String> nextQueries,
      Instant startedAt,
      Instant finishedAt) {
    public RetrievalRound {
      if (roundId == null || roundId.isBlank())
        throw new IllegalArgumentException("retrieval round id is required");
      if (sessionId == null || sessionId.isBlank())
        throw new IllegalArgumentException("retrieval round session id is required");
      if (roundNo <= 0)
        throw new IllegalArgumentException("retrieval round number must be positive");
      inputQueries = inputQueries == null ? List.of() : List.copyOf(inputQueries);
      decision = decision == null ? Decision.ANSWER : decision;
      missingInformation = missingInformation == null ? List.of() : List.copyOf(missingInformation);
      nextQueries = nextQueries == null ? List.of() : List.copyOf(nextQueries);
      startedAt = startedAt == null ? Instant.now() : startedAt;
      finishedAt = finishedAt == null ? startedAt : finishedAt;
    }
  }

  public record QueryEvidenceRef(
      String sessionId, String evidenceId, int firstSeenRound, String supportingQuery) {
    public QueryEvidenceRef {
      if (sessionId == null || sessionId.isBlank())
        throw new IllegalArgumentException("query evidence session id is required");
      if (evidenceId == null || evidenceId.isBlank())
        throw new IllegalArgumentException("query evidence id is required");
      if (firstSeenRound <= 0)
        throw new IllegalArgumentException("query evidence first seen round must be positive");
      supportingQuery = supportingQuery == null ? "" : supportingQuery;
    }
  }
}
