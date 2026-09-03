package cn.lgs.knowledgebase.application.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Application boundary for administrator-managed model configuration profiles. */
public final class ModelConfigurationApplicationService {
  private final Store store;

  public ModelConfigurationApplicationService(Store store) {
    this.store = Objects.requireNonNull(store, "model configuration store");
  }

  /** Legacy active-configuration view retained for compatibility with existing clients. */
  public View current() {
    return View.from(store.current());
  }

  /** Legacy active-configuration update retained for compatibility with existing clients. */
  public View update(UpdateCommand command) {
    return View.from(store.update(Objects.requireNonNull(command, "update command")));
  }

  public ProfilesView profiles() {
    List<ProfileView> profiles = store.profiles().stream().map(ProfileView::from).toList();
    String activeProfileId =
        profiles.stream()
            .filter(ProfileView::active)
            .map(ProfileView::profileId)
            .findFirst()
            .orElse("");
    return new ProfilesView(activeProfileId, profiles);
  }

  public ProfileView create(String name) {
    return ProfileView.from(store.create(requiredName(name)));
  }

  public ProfileView updateProfile(String profileId, ProfileUpdateCommand command) {
    return ProfileView.from(
        store.updateProfile(
            requiredId(profileId), Objects.requireNonNull(command, "profile update")));
  }

  public ProfileView copy(String profileId) {
    return ProfileView.from(store.copy(requiredId(profileId)));
  }

  public ProfileView activate(String profileId) {
    return ProfileView.from(store.activate(requiredId(profileId)));
  }

  public void delete(String profileId) {
    store.delete(requiredId(profileId));
  }

  public interface Store {
    EffectiveConfiguration current();

    EffectiveConfiguration update(UpdateCommand command);

    default List<ProfileConfiguration> profiles() {
      throw new UnsupportedOperationException("model configuration profiles are not supported");
    }

    default ProfileConfiguration create(String name) {
      throw new UnsupportedOperationException("model configuration profiles are not supported");
    }

    default ProfileConfiguration updateProfile(String profileId, ProfileUpdateCommand command) {
      throw new UnsupportedOperationException("model configuration profiles are not supported");
    }

    default ProfileConfiguration copy(String profileId) {
      throw new UnsupportedOperationException("model configuration profiles are not supported");
    }

    default ProfileConfiguration activate(String profileId) {
      throw new UnsupportedOperationException("model configuration profiles are not supported");
    }

    default void delete(String profileId) {
      throw new UnsupportedOperationException("model configuration profiles are not supported");
    }
  }

  public record EffectiveConfiguration(
      String chatBaseUrl,
      String chatApiKey,
      String smallModel,
      String largeModel,
      int chatTimeoutSeconds,
      String embeddingBaseUrl,
      String embeddingApiKey,
      String embeddingModel,
      int embeddingDimensions,
      int embeddingBatchSize,
      int embeddingTimeoutSeconds,
      String rerankBaseUrl,
      String rerankApiKey,
      String rerankModel,
      int rerankTimeoutSeconds,
      Instant updatedAt) {
    @Override
    public String toString() {
      return "EffectiveConfiguration[chatBaseUrl="
          + chatBaseUrl
          + ", chatApiKey=[REDACTED], smallModel="
          + smallModel
          + ", largeModel="
          + largeModel
          + ", chatTimeoutSeconds="
          + chatTimeoutSeconds
          + ", embeddingBaseUrl="
          + embeddingBaseUrl
          + ", embeddingApiKey=[REDACTED], embeddingModel="
          + embeddingModel
          + ", embeddingDimensions="
          + embeddingDimensions
          + ", embeddingBatchSize="
          + embeddingBatchSize
          + ", embeddingTimeoutSeconds="
          + embeddingTimeoutSeconds
          + ", rerankBaseUrl="
          + rerankBaseUrl
          + ", rerankApiKey=[REDACTED], rerankModel="
          + rerankModel
          + ", rerankTimeoutSeconds="
          + rerankTimeoutSeconds
          + ", updatedAt="
          + updatedAt
          + "]";
    }
  }

  public record ProfileConfiguration(
      String profileId,
      String name,
      boolean active,
      String chatBaseUrl,
      String chatApiKey,
      String smallModel,
      String largeModel,
      int chatTimeoutSeconds,
      String embeddingBaseUrl,
      String embeddingApiKey,
      String embeddingModel,
      int embeddingDimensions,
      int embeddingBatchSize,
      int embeddingTimeoutSeconds,
      String rerankBaseUrl,
      String rerankApiKey,
      String rerankModel,
      int rerankTimeoutSeconds,
      Instant createdAt,
      Instant updatedAt) {
    public EffectiveConfiguration effective() {
      return new EffectiveConfiguration(
          chatBaseUrl,
          chatApiKey,
          smallModel,
          largeModel,
          chatTimeoutSeconds,
          embeddingBaseUrl,
          embeddingApiKey,
          embeddingModel,
          embeddingDimensions,
          embeddingBatchSize,
          embeddingTimeoutSeconds,
          rerankBaseUrl,
          rerankApiKey,
          rerankModel,
          rerankTimeoutSeconds,
          updatedAt);
    }
  }

  /**
   * Null means keep the existing effective value; blank API keys also preserve the existing secret.
   */
  public record UpdateCommand(
      String chatBaseUrl,
      String chatApiKey,
      Boolean clearChatApiKey,
      String smallModel,
      String largeModel,
      Integer chatTimeoutSeconds,
      String embeddingBaseUrl,
      String embeddingApiKey,
      Boolean clearEmbeddingApiKey,
      String embeddingModel,
      Integer embeddingDimensions,
      Integer embeddingBatchSize,
      Integer embeddingTimeoutSeconds,
      String rerankBaseUrl,
      String rerankApiKey,
      Boolean clearRerankApiKey,
      String rerankModel,
      Integer rerankTimeoutSeconds) {}

  /** Complete editable snapshot for one named profile. Empty API keys are valid values. */
  public record ProfileUpdateCommand(
      String name,
      String chatBaseUrl,
      String chatApiKey,
      String smallModel,
      String largeModel,
      Integer chatTimeoutSeconds,
      String embeddingBaseUrl,
      String embeddingApiKey,
      String embeddingModel,
      Integer embeddingDimensions,
      Integer embeddingBatchSize,
      Integer embeddingTimeoutSeconds,
      String rerankBaseUrl,
      String rerankApiKey,
      String rerankModel,
      Integer rerankTimeoutSeconds) {}

  /** Legacy secret-redacted response. */
  public record View(
      String chatBaseUrl,
      boolean chatApiKeyConfigured,
      String smallModel,
      String largeModel,
      int chatTimeoutSeconds,
      String embeddingBaseUrl,
      boolean embeddingApiKeyConfigured,
      String embeddingModel,
      int embeddingDimensions,
      int embeddingBatchSize,
      int embeddingTimeoutSeconds,
      String rerankBaseUrl,
      boolean rerankApiKeyConfigured,
      String rerankModel,
      int rerankTimeoutSeconds,
      Instant updatedAt) {
    private static View from(EffectiveConfiguration value) {
      return new View(
          value.chatBaseUrl(),
          configured(value.chatApiKey()),
          value.smallModel(),
          value.largeModel(),
          value.chatTimeoutSeconds(),
          value.embeddingBaseUrl(),
          configured(value.embeddingApiKey()),
          value.embeddingModel(),
          value.embeddingDimensions(),
          value.embeddingBatchSize(),
          value.embeddingTimeoutSeconds(),
          value.rerankBaseUrl(),
          configured(value.rerankApiKey()),
          value.rerankModel(),
          value.rerankTimeoutSeconds(),
          value.updatedAt());
    }
  }

  /**
   * Admin-only profile response. API keys are represented by a stable mask rather than raw
   * credentials.
   */
  public record ProfileView(
      String profileId,
      String name,
      boolean active,
      String chatBaseUrl,
      boolean chatApiKeyConfigured,
      String chatApiKeyMasked,
      String smallModel,
      String largeModel,
      int chatTimeoutSeconds,
      String embeddingBaseUrl,
      boolean embeddingApiKeyConfigured,
      String embeddingApiKeyMasked,
      String embeddingModel,
      int embeddingDimensions,
      int embeddingBatchSize,
      int embeddingTimeoutSeconds,
      String rerankBaseUrl,
      boolean rerankApiKeyConfigured,
      String rerankApiKeyMasked,
      String rerankModel,
      int rerankTimeoutSeconds,
      Instant createdAt,
      Instant updatedAt) {
    private static ProfileView from(ProfileConfiguration value) {
      return new ProfileView(
          value.profileId(),
          value.name(),
          value.active(),
          value.chatBaseUrl(),
          configured(value.chatApiKey()),
          masked(value.chatApiKey()),
          value.smallModel(),
          value.largeModel(),
          value.chatTimeoutSeconds(),
          value.embeddingBaseUrl(),
          configured(value.embeddingApiKey()),
          masked(value.embeddingApiKey()),
          value.embeddingModel(),
          value.embeddingDimensions(),
          value.embeddingBatchSize(),
          value.embeddingTimeoutSeconds(),
          value.rerankBaseUrl(),
          configured(value.rerankApiKey()),
          masked(value.rerankApiKey()),
          value.rerankModel(),
          value.rerankTimeoutSeconds(),
          value.createdAt(),
          value.updatedAt());
    }
  }

  public record ProfilesView(String activeProfileId, List<ProfileView> profiles) {
    public ProfilesView {
      profiles = profiles == null ? List.of() : List.copyOf(profiles);
    }
  }

  private static String requiredId(String value) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("profile id is required");
    return value.trim();
  }

  private static String requiredName(String value) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("profile name is required");
    String name = value.trim();
    if (name.length() > 128) throw new IllegalArgumentException("profile name is too long");
    return name;
  }

  private static boolean configured(String value) {
    return value != null && !value.isBlank();
  }

  private static String masked(String value) {
    if (!configured(value)) return "";
    String text = value.trim();
    if (text.length() <= 8) return "••••" + text.substring(Math.max(0, text.length() - 2));
    return text.substring(0, 4) + "••••••••" + text.substring(text.length() - 4);
  }
}
