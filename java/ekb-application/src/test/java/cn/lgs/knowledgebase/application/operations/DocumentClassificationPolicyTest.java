package cn.lgs.knowledgebase.application.operations;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.*;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileMetadata;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentClassificationPolicyTest {
  @Test
  void uploadRejectsAnUnreadableExplicitScopeBeforeObjectOrRawWork() {
    User admin = user(Role.ADMIN, 30);
    assertDoesNotThrow(() -> DocumentClassificationPolicy.requireUploadAllowed(admin, 30));
    assertDoesNotThrow(() -> DocumentClassificationPolicy.requireUploadAllowed(admin, null));
    assertThrows(
        ManagedIngestionApplicationService.Forbidden.class,
        () -> DocumentClassificationPolicy.requireUploadAllowed(admin, 40));
    // Later metadata reclassification retains its existing administrator rule.
    assertDoesNotThrow(() -> DocumentClassificationPolicy.requireAllowed(admin, 40));
  }

  @Test
  void anExplicitClassificationRequiresAdminEvenWhenPublic() {
    User reader = user(Role.USER);
    assertDoesNotThrow(() -> DocumentClassificationPolicy.requireAllowed(reader, null));
    for (int level : List.of(10, 30, 127))
      assertThrows(
          ManagedIngestionApplicationService.Forbidden.class,
          () -> DocumentClassificationPolicy.requireAllowed(reader, level));
  }

  @Test
  void adminUsesTheExistingClassificationBounds() {
    for (int level : List.of(10, 30, 127))
      assertDoesNotThrow(
          () -> DocumentClassificationPolicy.requireAllowed(user(Role.ADMIN), level));
    for (int level : List.of(9, 128))
      assertThrows(
          IllegalArgumentException.class,
          () -> DocumentClassificationPolicy.requireAllowed(user(Role.ADMIN), level));
  }

  @Test
  void oldConstructorKeepsInheritanceAndOrdinaryMetadataPatchKeepsExplicitClassification() {
    var old = new FileMetadata("contract", "", "", "", "", null, List.of());
    assertNull(old.securityLevel());
    var classified = old.patch(null, null, null, null, null, null, false, List.of(), false, 30);
    assertEquals(30, classified.securityLevel());
    assertEquals(
        30,
        classified
            .patch("renamed", null, null, null, null, null, false, List.of(), false)
            .securityLevel());
  }

  private static User user(Role role) {
    return user(role, 127);
  }

  private static User user(Role role, int clearance) {
    return new User(
        "fixture-" + role,
        "fixture",
        "fixture",
        role,
        UserStatus.ACTIVE,
        null,
        Instant.EPOCH,
        Instant.EPOCH,
        clearance);
  }
}
