package cn.lgs.knowledgebase.infrastructure.raw;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.EffectiveConfiguration;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.Store;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.UpdateCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrozenRawEncodingConfigurationTest {
  @TempDir Path directory;
  private static final Store UNUSED =
      new Store() {
        public EffectiveConfiguration current() {
          throw new AssertionError("Startup must not load models or DB configuration");
        }

        public EffectiveConfiguration update(UpdateCommand command) {
          throw new AssertionError();
        }
      };

  @Test
  void unavailableRawTokenizerDoesNotPreventCreatingQueryDependencies() {
    try (var frozen =
        new FrozenRawEncodingConfiguration(
            "", new ObjectMapper(), UNUSED, "full-document-input-v1")) {
      assertNotNull(frozen.tokens());
      assertNotNull(frozen.profiles());
      var failure =
          assertThrows(IllegalStateException.class, () -> frozen.tokens().count("正常查询仍可启动"));
      assertTrue(failure.getMessage().startsWith("TOKENIZER_UNAVAILABLE:"));
      assertThrows(IllegalStateException.class, () -> frozen.profiles().get());
    }
  }

  @Test
  void unknownModelAndIncompleteManifestAreNeverEstimatedOrDownloaded() throws Exception {
    Path manifest = directory.resolve("manifest.json");
    Files.writeString(
        manifest,
        "{\"model\":\"unknown\",\"modelRevision\":\"abc\",\"tokenizerRevision\":\"abc\",\"dimensions\":1024}");
    try (var frozen =
        new FrozenRawEncodingConfiguration(
            manifest.toString(), new ObjectMapper(), UNUSED, "full-document-input-v1")) {
      var failure = assertThrows(IllegalStateException.class, () -> frozen.tokens().count("文本"));
      assertTrue(failure.getMessage().startsWith("TOKENIZER_UNAVAILABLE:"));
      assertNotNull(failure.getCause());
    }
  }

  @Test
  void preprocessingMustBeExplicitAndClosedTokenizerCannotBeUsed() {
    var frozen =
        new FrozenRawEncodingConfiguration("manifest.json", new ObjectMapper(), UNUSED, "");
    assertThrows(IllegalStateException.class, () -> frozen.tokens().identity());
    frozen.close();
    assertThrows(IllegalStateException.class, () -> frozen.tokens().count("文本"));
  }
}
