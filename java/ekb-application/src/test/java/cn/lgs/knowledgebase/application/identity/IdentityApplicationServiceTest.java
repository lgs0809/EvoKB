package cn.lgs.knowledgebase.application.identity;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.port.Ports.PasswordHasher;
import cn.lgs.knowledgebase.application.port.Ports.SessionRepository;
import cn.lgs.knowledgebase.application.port.Ports.TransactionPort;
import cn.lgs.knowledgebase.application.port.Ports.UserRepository;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.Role;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.Session;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.User;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.UserStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IdentityApplicationServiceTest {
  @Test
  void setupCanOnlyCreateTheFirstAdministrator() {
    Fixture fixture = new Fixture();
    User admin =
        fixture.service.setupAdmin(
            new IdentityApplicationService.CreateUser(
                "admin", "Admin", chars("0123456789"), Role.USER));
    assertEquals(Role.ADMIN, admin.role());
    assertThrows(
        IdentityApplicationService.Conflict.class,
        () ->
            fixture.service.setupAdmin(
                new IdentityApplicationService.CreateUser(
                    "admin2", "Admin 2", chars("0123456789"), Role.ADMIN)));
  }

  @Test
  void cannotDisableOrDemoteLastActiveAdministrator() {
    Fixture fixture = new Fixture();
    User admin =
        fixture.service.setupAdmin(
            new IdentityApplicationService.CreateUser(
                "admin", "Admin", chars("0123456789"), Role.ADMIN));
    assertThrows(
        IdentityApplicationService.Conflict.class,
        () ->
            fixture.service.updateUser(
                admin.id(), new IdentityApplicationService.UpdateUser(null, Role.USER, null)));
    assertThrows(
        IdentityApplicationService.Conflict.class,
        () ->
            fixture.service.updateUser(
                admin.id(),
                new IdentityApplicationService.UpdateUser(null, null, UserStatus.DISABLED)));
  }

  @Test
  void passwordChangeRevokesExistingSessions() {
    Fixture fixture = new Fixture();
    fixture.service.setupAdmin(
        new IdentityApplicationService.CreateUser(
            "admin", "Admin", chars("0123456789"), Role.ADMIN));
    var login = fixture.service.login("admin", chars("0123456789"));
    assertEquals("admin", fixture.service.authenticate(login.token()).username());

    fixture.service.changePassword(login.user().id(), chars("0123456789"), chars("abcdefghij"));
    assertThrows(
        IdentityApplicationService.Unauthorized.class,
        () -> fixture.service.authenticate(login.token()));
    assertThrows(
        IdentityApplicationService.InvalidCredentials.class,
        () -> fixture.service.login("admin", chars("0123456789")));
    assertEquals("admin", fixture.service.login("admin", chars("abcdefghij")).user().username());
  }

  @Test
  void clearanceChangesAffectExistingSessionAndSurviveLoginAndProfileChanges() {
    Fixture f = new Fixture();
    User admin =
        f.service.setupAdmin(
            new IdentityApplicationService.CreateUser(
                "admin", "Admin", chars("0123456789"), Role.ADMIN));
    var login = f.service.login("admin", chars("0123456789"));
    f.service.updateUser(
        admin.id(), new IdentityApplicationService.UpdateUser("Renamed", null, null, 30));
    assertEquals(30, f.service.authenticate(login.token()).securityClearance());
    assertEquals(30, f.service.login("admin", chars("0123456789")).user().securityClearance());
    f.service.updateUser(
        admin.id(), new IdentityApplicationService.UpdateUser(null, null, null, 10));
    assertEquals(10, f.service.authenticate(login.token()).securityClearance());
  }

  private static char[] chars(String value) {
    return value.toCharArray();
  }

  @Test
  void loginPreservesProfileChangedBeforeSessionTransaction() {
    Fixture fixture = new Fixture();
    User user =
        fixture.service.setupAdmin(
            new IdentityApplicationService.CreateUser(
                "admin", "Admin", chars("0123456789"), Role.ADMIN));
    fixture.tx.beforeWork =
        () ->
            fixture.store.update(
                user.withRoleAndStatus(Role.USER, UserStatus.ACTIVE, Instant.now())
                    .withDisplayName("Updated profile", Instant.now()));
    var login = fixture.service.login("admin", chars("0123456789"));
    assertEquals(Role.USER, login.user().role());
    assertEquals("Updated profile", login.user().displayName());
    assertEquals(Role.USER, fixture.service.authenticate(login.token()).role());
  }

  @Test
  void loginCannotReactivateAccountDisabledBeforeSessionTransaction() {
    Fixture fixture = new Fixture();
    User user =
        fixture.service.setupAdmin(
            new IdentityApplicationService.CreateUser(
                "admin", "Admin", chars("0123456789"), Role.ADMIN));
    fixture.tx.beforeWork =
        () ->
            fixture.store.update(
                user.withRoleAndStatus(Role.ADMIN, UserStatus.DISABLED, Instant.now()));
    assertThrows(
        IdentityApplicationService.InvalidCredentials.class,
        () -> fixture.service.login("admin", chars("0123456789")));
    assertFalse(fixture.store.findById(user.id()).orElseThrow().active());
    assertTrue(fixture.store.sessions.isEmpty());
  }

  @Test
  void loginCannotUsePasswordReplacedBeforeSessionTransaction() {
    Fixture fixture = new Fixture();
    User user =
        fixture.service.setupAdmin(
            new IdentityApplicationService.CreateUser(
                "admin", "Admin", chars("0123456789"), Role.ADMIN));
    fixture.tx.beforeWork = () -> fixture.store.updatePasswordHash(user.id(), "hash:abcdefghij");
    assertThrows(
        IdentityApplicationService.InvalidCredentials.class,
        () -> fixture.service.login("admin", chars("0123456789")));
    assertTrue(fixture.store.sessions.isEmpty());
  }

  private static final class Fixture {
    private final FakeStore store = new FakeStore();
    private final DirectTx tx = new DirectTx();
    private final IdentityApplicationService service =
        new IdentityApplicationService(
            store, store, new FakePasswordHasher(), tx, Duration.ofHours(12));
  }

  private static final class DirectTx implements TransactionPort {
    private Runnable beforeWork;

    @Override
    public <T> T required(java.util.function.Supplier<T> work) {
      if (beforeWork != null) {
        Runnable action = beforeWork;
        beforeWork = null;
        action.run();
      }
      return work.get();
    }
  }

  private static final class FakePasswordHasher implements PasswordHasher {
    @Override
    public String hash(char[] password) {
      return "hash:" + new String(password);
    }

    @Override
    public boolean matches(char[] password, String encodedHash) {
      return encodedHash.equals(hash(password));
    }
  }

  private static final class FakeStore implements UserRepository, SessionRepository {
    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, String> passwordHashes = new LinkedHashMap<>();
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    @Override
    public long count() {
      return users.size();
    }

    @Override
    public long countLocked() {
      return count();
    }

    @Override
    public long countActiveAdmins() {
      return users.values().stream().filter(u -> u.active() && u.administrator()).count();
    }

    @Override
    public long countActiveAdminsLocked() {
      return countActiveAdmins();
    }

    @Override
    public Optional<User> findById(String id) {
      return Optional.ofNullable(users.get(id));
    }

    @Override
    public Optional<User> findByIdLocked(String id) {
      return findById(id);
    }

    @Override
    public Optional<User> findByUsername(String username) {
      return users.values().stream().filter(u -> u.username().equals(username)).findFirst();
    }

    @Override
    public List<User> list(int offset, int limit) {
      return users.values().stream()
          .sorted(Comparator.comparing(User::createdAt))
          .skip(offset)
          .limit(limit)
          .toList();
    }

    @Override
    public void save(User user, String passwordHash) {
      if (findByUsername(user.username()).isPresent())
        throw new IllegalStateException("duplicate username");
      users.put(user.id(), user);
      passwordHashes.put(user.id(), passwordHash);
    }

    @Override
    public void update(User user) {
      users.put(user.id(), user);
    }

    @Override
    public void updatePasswordHash(String userId, String passwordHash) {
      passwordHashes.put(userId, passwordHash);
    }

    @Override
    public Optional<String> passwordHash(String userId) {
      return Optional.ofNullable(passwordHashes.get(userId));
    }

    @Override
    public void save(Session session) {
      sessions.put(session.id(), session);
    }

    @Override
    public Optional<Session> findByTokenHash(String tokenHash) {
      return Optional.ofNullable(sessions.get(tokenHash));
    }

    @Override
    public void touch(String sessionId, Instant now) {}

    @Override
    public void delete(String sessionId) {
      sessions.remove(sessionId);
    }

    @Override
    public void deleteByUser(String userId) {
      sessions.values().removeIf(s -> s.userId().equals(userId));
    }

    @Override
    public int deleteExpired(Instant now) {
      int before = sessions.size();
      sessions.values().removeIf(s -> s.expiredAt(now));
      return before - sessions.size();
    }
  }
}
