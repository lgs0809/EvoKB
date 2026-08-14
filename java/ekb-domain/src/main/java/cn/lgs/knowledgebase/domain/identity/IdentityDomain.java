package cn.lgs.knowledgebase.domain.identity;

import java.time.Instant;
import java.util.Objects;

public final class IdentityDomain {
    private IdentityDomain() {}

    public enum Role { ADMIN, USER }
    public enum UserStatus { ACTIVE, DISABLED }

    public record User(String id, String username, Role role, UserStatus status, Instant createdAt) {
        public User {
            id = requireText(id, "user id");
            username = requireText(username, "username");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(createdAt, "createdAt");
        }

        public boolean active() {
            return status == UserStatus.ACTIVE;
        }

        public boolean administrator() {
            return role == Role.ADMIN;
        }
    }

    public record Session(String id, String userId, String tokenHash, Instant expiresAt, Instant createdAt) {
        public Session {
            id = requireText(id, "session id");
            userId = requireText(userId, "user id");
            tokenHash = requireText(tokenHash, "token hash");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(createdAt, "createdAt");
            if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("session expiry must be after creation");
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
