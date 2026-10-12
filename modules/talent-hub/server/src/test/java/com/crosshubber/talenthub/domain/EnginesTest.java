package com.crosshubber.talenthub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.crosshubber.talenthub.agent.LlmClient;
import com.crosshubber.talenthub.config.TalentHubProperties;
import com.crosshubber.talenthub.settings.CryptoService;
import com.crosshubber.talenthub.settings.ModuleSettingEntity;
import com.crosshubber.talenthub.settings.ModuleSettingRepository;
import com.crosshubber.talenthub.settings.ModuleSettingsService;

import tools.jackson.databind.json.JsonMapper;

/** LLM + JEV engine paths (P3.1): fail-closed when unconfigured, parse on configured answers. */
class EnginesTest {

  private CandidateProfileEntity candidate;

  @BeforeEach
  void setUp() {
    candidate = new CandidateProfileEntity();
    org.springframework.test.util.ReflectionTestUtils.setField(candidate, "id", UUID.randomUUID());
    candidate.setName("Ana");
    candidate.setSkills(List.of("java"));
    candidate.setSeniority("senior");
    candidate.setLanguages(List.of("en"));
    candidate.setAvailability("immediate");
    candidate.setExpectations(Map.of("rate", 450));
  }

  private static PositionRequirements requirements() {
    return PositionRequirements.from(Map.of("skills", List.of("java"), "rate", 500));
  }

  @Test
  void llmEngineParsesTheThreeDimensionJson() throws Exception {
    LlmClient llm = mock(LlmClient.class);
    when(llm.isConfigured()).thenReturn(true);
    when(llm.complete(anyString(), anyString()))
        .thenReturn(
            "{\"job\":{\"verdict\":\"strong\",\"rationale\":\"skills match\",\"score\":8},"
                + "\"environment\":{\"verdict\":\"maybe\",\"rationale\":\"no location data\"},"
                + "\"value\":{\"verdict\":\"strong\",\"rationale\":\"under budget\"}}");
    LlmFitEngine engine = new LlmFitEngine(llm, new JsonMapper());

    FitVerdict verdict = engine.assess(candidate, requirements(), "u1");

    assertThat(verdict.engine()).isEqualTo("llm");
    assertThat(verdict.dimensions().get("job").verdict()).isEqualTo("strong");
    assertThat(verdict.dimensions().get("job").score()).isEqualTo("8.0");
    assertThat(verdict.dimensions().get("environment").verdict()).isEqualTo("maybe");
    assertThat(verdict.overall()).isEqualTo("maybe");
  }

  @Test
  void llmEngineToleratesFencedAndIncompleteAnswers() throws Exception {
    LlmClient llm = mock(LlmClient.class);
    when(llm.isConfigured()).thenReturn(true);
    when(llm.complete(anyString(), anyString()))
        .thenReturn("```json\n{\"job\":{\"verdict\":\"strong\",\"rationale\":\"fit\"}}\n```");
    LlmFitEngine engine = new LlmFitEngine(llm, new JsonMapper());

    FitVerdict verdict = engine.assess(candidate, requirements(), "u1");

    assertThat(verdict.dimensions().get("job").verdict()).isEqualTo("strong");
    assertThat(verdict.dimensions().get("environment").verdict()).isEqualTo(FitVerdict.MAYBE);
    assertThat(verdict.dimensions().get("value").verdict()).isEqualTo(FitVerdict.MAYBE);
  }

  @Test
  void llmEngineFailsClosedWhenUnconfigured() {
    LlmClient llm = mock(LlmClient.class);
    when(llm.isConfigured()).thenReturn(false);
    IllegalStateException expected =
        new IllegalStateException("llm is not configured (module settings or TALENTHUB_LLM_* env)");
    LlmFitEngine engine =
        new LlmFitEngine(llm, new JsonMapper()) {
          @Override
          public FitVerdict assess(CandidateProfileEntity c, PositionRequirements r, String actor)
              throws Exception {
            if (!llm.isConfigured()) {
              throw expected;
            }
            return super.assess(c, r, actor);
          }
        };

    assertThatThrownBy(() -> engine.assess(candidate, requirements(), "u1")).isSameAs(expected);
  }

  @Test
  void jevEngineFailsClosedWhenUnconfigured() {
    var settings = testSettings();
    JevFitEngine engine = new JevFitEngine(settings, RestClient.builder());

    assertThatThrownBy(() -> engine.assess(candidate, requirements(), "u1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("jev engine not configured");
  }

  @Test
  void jevEngineSurfacesTransportFailuresToTheCaller() {
    var settings = testSettings();
    settings.update(
        new ModuleSettingsService.SettingsUpdateRequest(
            null,
            new ModuleSettingsService.MatchingUpdate(null, "http://127.0.0.1:1", "jev-key", 1000),
            null,
            null));
    JevFitEngine engine = new JevFitEngine(settings, RestClient.builder());

    assertThatThrownBy(() -> engine.assess(candidate, requirements(), "u1"))
        .isInstanceOf(Exception.class);
  }

  private ModuleSettingsService testSettings() {
    ModuleSettingRepository repo = mock(ModuleSettingRepository.class);
    when(repo.findByKey(anyString())).thenReturn(Optional.empty());
    when(repo.save(any(ModuleSettingEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    TalentHubProperties props = new TalentHubProperties();
    props.setCryptoKey("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    return new ModuleSettingsService(repo, new CryptoService(props), props, new JsonMapper());
  }
}
