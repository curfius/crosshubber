package com.crosshubber.talenthub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.crosshubber.talenthub.config.TalentHubProperties;
import com.crosshubber.talenthub.settings.CryptoService;
import com.crosshubber.talenthub.settings.ModuleSettingEntity;
import com.crosshubber.talenthub.settings.ModuleSettingRepository;
import com.crosshubber.talenthub.settings.ModuleSettingsService;

import tools.jackson.databind.json.JsonMapper;

/** Engine selection (P3.1): override wins, settings default applies, unknown keys fail fast. */
class FitEngineSelectorTest {

  private static final String CRYPTO_KEY =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private ModuleSettingsService settings;
  private FitEngineSelector selector;

  private static FitEngine engine(String key) {
    return new FitEngine() {
      @Override
      public String key() {
        return key;
      }

      @Override
      public FitVerdict assess(
          CandidateProfileEntity candidate, PositionRequirements requirements, String actor) {
        return FitVerdict.of(
            key,
            FitVerdict.dimension(FitVerdict.STRONG, "stub", null),
            FitVerdict.dimension(FitVerdict.STRONG, "stub", null),
            FitVerdict.dimension(FitVerdict.STRONG, "stub", null));
      }
    };
  }

  @BeforeEach
  void setUp() {
    ModuleSettingRepository repo = mock(ModuleSettingRepository.class);
    // Stateful in-memory store so update-then-read round-trips like the real DB.
    java.util.Map<String, ModuleSettingEntity> store =
        new java.util.concurrent.ConcurrentHashMap<>();
    when(repo.save(any(ModuleSettingEntity.class)))
        .thenAnswer(
            inv -> {
              ModuleSettingEntity entity = inv.getArgument(0);
              store.put(entity.getKey(), entity);
              return entity;
            });
    when(repo.findByKey(any(String.class)))
        .thenAnswer(inv -> Optional.ofNullable(store.get((String) inv.getArgument(0))));
    TalentHubProperties props = new TalentHubProperties();
    props.setCryptoKey(CRYPTO_KEY);
    settings = new ModuleSettingsService(repo, new CryptoService(props), props, new JsonMapper());
    selector =
        new FitEngineSelector(List.of(engine("rules"), engine("jev"), engine("llm")), settings);
  }

  @Test
  void overrideWinsAndUnknownOverridesFailFast() {
    assertThat(selector.select("jev").key()).isEqualTo("jev");
    assertThat(selector.select("rules").key()).isEqualTo("rules");

    assertThatThrownBy(() -> selector.select("nope"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown fit engine");
  }

  @Test
  void settingsDefaultAppliesWithoutOverride() {
    // Matching group absent → default engine "rules".
    assertThat(selector.select(null).key()).isEqualTo("rules");
    assertThat(selector.select("").key()).isEqualTo("rules");
  }

  @Test
  void storedEngineSettingSelectsTheConfiguredEngine() {
    settings.update(
        new ModuleSettingsService.SettingsUpdateRequest(
            null, new ModuleSettingsService.MatchingUpdate("llm", null, null, null), null, null));

    assertThat(selector.select(null).key()).isEqualTo("llm");
  }

  @Test
  void storedEngineAsOverrideStillWins() {
    settings.update(
        new ModuleSettingsService.SettingsUpdateRequest(
            null, new ModuleSettingsService.MatchingUpdate("llm", null, null, null), null, null));

    assertThat(selector.select("rules").key()).isEqualTo("rules");
  }

  @Test
  void knownKeysCoverAllEngines() {
    assertThat(selector.knownKeys()).containsExactlyInAnyOrder("rules", "jev", "llm");
  }
}
