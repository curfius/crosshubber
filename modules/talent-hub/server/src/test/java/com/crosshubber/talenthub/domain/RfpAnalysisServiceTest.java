package com.crosshubber.talenthub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.crosshubber.talenthub.agent.LlmClient;
import com.crosshubber.talenthub.settings.ModuleSettingsService;

import tools.jackson.databind.json.JsonMapper;

/** Analysis fail-soft matrix: unconfigured, happy, malformed, fenced JSON (P1.8). */
class RfpAnalysisServiceTest {

  private LlmClient llm;
  private ModuleSettingsService settings;
  private RfpAnalysisService service;

  @BeforeEach
  void setUp() {
    llm = mock(LlmClient.class);
    settings = mock(ModuleSettingsService.class);
    when(settings.effectiveLlm())
        .thenReturn(new ModuleSettingsService.EffectiveLlm("https://llm/v1", "k", "gpt-x"));
    service = new RfpAnalysisService(llm, settings, new JsonMapper());
  }

  @Test
  void unconfiguredYieldsUnconfiguredDraftStillViable() throws Exception {
    when(llm.isConfigured()).thenReturn(false);

    RfpAnalysisService.AnalysisResult result = service.analyze("Need two Java devs", null, null);

    assertThat(result.analysis().get("status")).isEqualTo("unconfigured");
    assertThat(result.title()).isNull();
    assertThat((List<?>) result.analysis().get("positions")).isEmpty();
  }

  @Test
  void happyPathExtractsPositionsRequirementsAndBackfill() throws Exception {
    when(llm.isConfigured()).thenReturn(true);
    when(llm.complete(anyString(), anyString()))
        .thenReturn(
            "{\"title\":\"Java squad\",\"deadline\":\"2026-11-01T00:00:00Z\","
                + "\"positions\":[{\"title\":\"Senior Java\",\"headcount\":2},"
                + "{\"title\":\"React dev\",\"headcount\":1}],"
                + "\"requirements\":{\"skills\":[\"java\",\"spring\"],\"seniority\":\"senior\","
                + "\"languages\":[\"en\"]},"
                + "\"openQuestions\":[\"Is remote work allowed?\"]}");

    RfpAnalysisService.AnalysisResult result = service.analyze("Need Java devs", "hint", null);

    assertThat(result.analysis().get("status")).isEqualTo("configured");
    assertThat(result.analysis().get("model")).isEqualTo("gpt-x");
    assertThat((List<?>) result.analysis().get("positions")).hasSize(2);
    assertThat(result.analysis().get("openQuestions"))
        .isEqualTo(List.of("Is remote work allowed?"));
    assertThat(result.title()).isEqualTo("Java squad");
    assertThat(result.deadline()).isNotNull();
    assertThat(result.requirements())
        .containsEntry("skills", List.of("java", "spring"))
        .containsEntry("seniority", "senior");
  }

  @Test
  void malformedResponseYieldsFailedStatus() throws Exception {
    when(llm.isConfigured()).thenReturn(true);
    when(llm.complete(anyString(), anyString())).thenReturn("I think we need java people. Yes.");

    RfpAnalysisService.AnalysisResult result = service.analyze("Need Java devs", null, null);

    assertThat(result.analysis().get("status")).isEqualTo("failed");
    assertThat(result.title()).isNull();
  }

  @Test
  void fencedJsonResponseIsTolerated() throws Exception {
    when(llm.isConfigured()).thenReturn(true);
    when(llm.complete(anyString(), anyString()))
        .thenReturn(
            "```json\n{\"title\":\"Fenced\",\"positions\":[{\"title\":\"Java\","
                + "\"headcount\":3}]}\n```");

    RfpAnalysisService.AnalysisResult result = service.analyze("Need Java devs", null, null);

    assertThat(result.analysis().get("status")).isEqualTo("configured");
    assertThat(result.title()).isEqualTo("Fenced");
    assertThat((List<?>) result.analysis().get("positions")).hasSize(1);
  }

  @Test
  void transportErrorYieldsFailedStatus() throws Exception {
    when(llm.isConfigured()).thenReturn(true);
    when(llm.complete(anyString(), anyString())).thenThrow(new RuntimeException("boom"));

    RfpAnalysisService.AnalysisResult result = service.analyze("Need Java devs", null, null);

    assertThat(result.analysis().get("status")).isEqualTo("failed");
    assertThat(result.requirements()).isEqualTo(Map.of());
  }
}
