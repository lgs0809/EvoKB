package cn.lgs.knowledgebase.domain.identity;

import java.time.Instant;
import java.util.Objects;

public final class IdentityDomain {
  private IdentityDomain() {}

  public enum Role {
    ADMIN,
    USER
  }

  public enum UserStatus {
    ACTIVE,
    DISABLED
  }

  public record User(
      String id,
      String username,
      String displayName,
      Role role,
      UserStatus status,
      Instant lastLoginAt,
      Instant createdAt,
      Instant updatedAt,
      int securityClearance) {
    public User(
        String id,
        String username,
        String displayName,
        Role role,
        UserStatus status,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt) {
      this(id, username, displayName, role, status, lastLoginAt, createdAt, updatedAt, 10);
    }

    public User {
      id = requireText(id, "user id");
      username = requireText(username, "username");
      displayName = displayName == null || displayName.isBlank() ? username : displayName.trim();
      if (displayName.length() > 128) throw new IllegalArgumentException("displayName is too long");
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(createdAt, "createdAt");
      updatedAt = updatedAt == null ? createdAt : updatedAt;
      if (securityClearance < 10 || securityClearance > 127)
        throw new IllegalArgumentException("security clearance must be between 10 and 127");
    }

    public boolean active() {
      return status == UserStatus.ACTIVE;
    }

    public boolean administrator() {
      return role == Role.ADMIN;
    }

    public User withRoleAndStatus(Role nextRole, UserStatus nextStatus, Instant now) {
      return new User(
          id,
          username,
          displayName,
          nextRole,
          nextStatus,
          lastLoginAt,
          createdAt,
          now,
          securityClearance);
    }

    public User withDisplayName(String nextDisplayName, Instant now) {
      return new User(
          id,
          username,
          nextDisplayName,
          role,
          status,
          lastLoginAt,
          createdAt,
          now,
          securityClearance);
    }

    public User withLastLogin(Instant now) {
      return new User(
          id, username, displayName, role, status, now, createdAt, now, securityClearance);
    }

    public User withSecurityClearance(int clearance, Instant now) {
      return new User(
          id, username, displayName, role, status, lastLoginAt, createdAt, now, clearance);
    }
  }

  public record Session(
      String id, String userId, String tokenHash, Instant expiresAt, Instant createdAt) {
    public Session {
      id = requireText(id, "session id");
      userId = requireText(userId, "user id");
      tokenHash = requireText(tokenHash, "token hash");
      Objects.requireNonNull(expiresAt, "expiresAt");
      Objects.requireNonNull(createdAt, "createdAt");
      if (!expiresAt.isAfter(createdAt))
        throw new IllegalArgumentException("session expiry must be after creation");
    }

    public boolean expiredAt(Instant now) {
      return !expiresAt.isAfter(now);
    }
  }

  public record AccessPolicy(boolean userUploadEnabled) {
    public boolean canUpload(User user) {
      Objects.requireNonNull(user, "user");
      return user.active() && (user.administrator() || userUploadEnabled);
    }
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value.trim();
  }
}
