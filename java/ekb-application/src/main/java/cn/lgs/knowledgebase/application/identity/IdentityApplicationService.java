package cn.lgs.knowledgebase.application.identity;

import static cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256;

import cn.lgs.knowledgebase.application.port.Ports.PasswordHasher;
import cn.lgs.knowledgebase.application.port.Ports.SessionRepository;
import cn.lgs.knowledgebase.application.port.Ports.TransactionPort;
import cn.lgs.knowledgebase.application.port.Ports.UserRepository;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.Role;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.Session;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.User;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.UserStatus;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public final class IdentityApplicationService {
  private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,64}$");
  private final UserRepository users;
  private final SessionRepository sessions;
  private final PasswordHasher passwords;
  private final TransactionPort transactions;
  private final Duration sessionTtl;
  private final Clock clock;
  private final SecureRandom random;

  public IdentityApplicationService(
      UserRepository users,
      SessionRepository sessions,
      PasswordHasher passwords,
      TransactionPort transactions,
      Duration sessionTtl) {
    this(
        users,
        sessions,
        passwords,
        transactions,
        sessionTtl,
        Clock.systemUTC(),
        new SecureRandom());
  }

  IdentityApplicationService(
      UserRepository users,
      SessionRepository sessions,
      PasswordHasher passwords,
      TransactionPort transactions,
      Duration sessionTtl,
      Clock clock,
      SecureRandom random) {
    this.users = require(users, "users");
    this.sessions = require(sessions, "sessions");
    this.passwords = require(passwords, "passwords");
    this.transactions = require(transactions, "transactions");
    this.sessionTtl =
        sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()
            ? Duration.ofHours(12)
            : sessionTtl;
    this.clock = require(clock, "clock");
    this.random = require(random, "random");
  }

  public Status status() {
    long count = users.count();
    return new Status(count == 0, count);
  }

  public User setupAdmin(CreateUser command) {
    validateCreate(command);
    return transactions.required(
        () -> {
          if (users.countLocked() != 0) throw new Conflict("initial setup is already complete");
          return create(command.username(), command.displayName(), command.password(), Role.ADMIN);
        });
  }

  public LoginResult login(String username, char[] password) {
    String normalized = username == null ? "" : username.trim();
    User user = users.findByUsername(normalized).orElseThrow(InvalidCredentials::new);
    String storedHash = users.passwordHash(user.id()).orElseThrow(InvalidCredentials::new);
    if (!user.active() || !passwords.matches(password == null ? new char[0] : password, storedHash))
      throw new InvalidCredentials();
    Instant now = clock.instant();
    String token = newToken();
    String tokenHash = sha256(token);
    Session session = new Session(tokenHash, user.id(), tokenHash, now.plus(sessionTtl), now);
    User loggedIn = user.withLastLogin(now);
    transactions.required(
        () -> {
          sessions.save(session);
          users.update(loggedIn);
        });
    return new LoginResult(loggedIn, token, session.expiresAt());
  }

  public User authenticate(String token) {
    if (token == null || token.isBlank()) throw new Unauthorized();
    Instant now = clock.instant();
    String hash = sha256(token.trim());
    Session session = sessions.findByTokenHash(hash).orElseThrow(Unauthorized::new);
    if (session.expiredAt(now)) {
      sessions.delete(session.id());
      throw new Unauthorized();
    }
    User user =
        users.findById(session.userId()).filter(User::active).orElseThrow(Unauthorized::new);
    sessions.touch(session.id(), now);
    return user;
  }

  public void logout(String token) {
    if (token != null && !token.isBlank()) sessions.delete(sha256(token.trim()));
  }

  public List<User> listUsers(int offset, int limit) {
    return users.list(Math.max(0, offset), limit <= 0 ? 100 : Math.min(limit, 500));
  }

  public User createUser(CreateUser command) {
    validateCreate(command);
    return transactions.required(
        () -> {
          if (users.findByUsername(command.username().trim()).isPresent())
            throw new Conflict("username already exists");
          return create(
              command.username(), command.displayName(), command.password(), command.role());
        });
  }

  public User updateUser(String userId, UpdateUser command) {
    if (userId == null || userId.isBlank()) throw new Validation("user_id is required");
    if (command == null) throw new Validation("update is required");
    if (command.displayName() != null && command.displayName().trim().length() > 128)
      throw new Validation("display_name is too long");
    return transactions.required(
        () -> {
          User current =
              users.findByIdLocked(userId.trim()).orElseThrow(() -> new NotFound("user not found"));
          Role nextRole = command.role() == null ? current.role() : command.role();
          UserStatus nextStatus = command.status() == null ? current.status() : command.status();
          if (current.administrator()
              && current.active()
              && (nextRole != Role.ADMIN || nextStatus != UserStatus.ACTIVE)
              && users.countActiveAdminsLocked() <= 1) {
            throw new Conflict("cannot disable or demote the last active admin");
          }
          Instant now = clock.instant();
          User next = current.withRoleAndStatus(nextRole, nextStatus, now);
          if (command.displayName() != null)
            next = next.withDisplayName(command.displayName(), now);
          users.update(next);
          if (next.status() == UserStatus.DISABLED) sessions.deleteByUser(next.id());
          return next;
        });
  }

  public void changePassword(String userId, char[] currentPassword, char[] newPassword) {
    validatePassword(newPassword);
    transactions.required(
        () -> {
          User user =
              users.findByIdLocked(userId).filter(User::active).orElseThrow(Unauthorized::new);
          String storedHash = users.passwordHash(user.id()).orElseThrow(Unauthorized::new);
          if (!passwords.matches(
              currentPassword == null ? new char[0] : currentPassword, storedHash)) {
            throw new InvalidCurrentPassword();
          }
          users.updatePasswordHash(user.id(), passwords.hash(newPassword));
          sessions.deleteByUser(user.id());
        });
  }

  public void resetPassword(String userId, char[] newPassword) {
    validatePassword(newPassword);
    transactions.required(
        () -> {
          User user =
              users.findByIdLocked(userId).orElseThrow(() -> new NotFound("user not found"));
          users.updatePasswordHash(user.id(), passwords.hash(newPassword));
          sessions.deleteByUser(user.id());
        });
  }

  private User create(String username, String displayName, char[] password, Role role) {
    Instant now = clock.instant();
    String normalized = username.trim();
    User user =
        new User(
            "usr_" + UUID.randomUUID().toString().replace("-", ""),
            normalized,
            displayName == null || displayName.isBlank() ? normalized : displayName.trim(),
            role,
            UserStatus.ACTIVE,
            null,
            now,
            now);
    users.save(user, passwords.hash(password));
    return user;
  }

  private String newToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public record Status(boolean setupRequired, long users) {}

  public record CreateUser(String username, String displayName, char[] password, Role role) {}

  public record UpdateUser(String displayName, Role role, UserStatus status) {}

  public record LoginResult(User user, String token, Instant expiresAt) {}

  public static class IdentityException extends RuntimeException {
    public IdentityException(String message) {
      super(message);
    }
  }

  public static final class Validation extends IdentityException {
    public Validation(String message) {
      super(message);
    }
  }

  public static final class Conflict extends IdentityException {
    public Conflict(String message) {
      super(message);
    }
  }

  public static final class NotFound extends IdentityException {
    public NotFound(String message) {
      super(message);
    }
  }

  public static final class Unauthorized extends IdentityException {
    public Unauthorized() {
      super("unauthorized");
    }
  }

  public static final class InvalidCredentials extends IdentityException {
    public InvalidCredentials() {
      super("invalid username or password");
    }
  }

  public static final class InvalidCurrentPassword extends IdentityException {
    public InvalidCurrentPassword() {
      super("current password is incorrect");
    }
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " is required");
    return value;
  }

  private static void validateCreate(CreateUser command) {
    if (command == null) throw new Validation("create user request is required");
    String username = command.username() == null ? "" : command.username().trim();
    if (!USERNAME.matcher(username).matches()) throw new Validation("invalid username");
    if (command.role() == null) throw new Validation("role must be ADMIN or USER");
    if (command.displayName() != null && command.displayName().trim().length() > 128)
      throw new Validation("display_name is too long");
    validatePassword(command.password());
  }

  private static void validatePassword(char[] password) {
    int length = password == null ? 0 : password.length;
    if (length < 10) throw new Validation("password must be at least 10 characters");
    if (length > 256) throw new Validation("password is too long");
  }
}
