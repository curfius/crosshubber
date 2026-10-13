package com.crosshubber.talenthub.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.crosshubber.talenthub.config.TalentHubProperties;

import tools.jackson.databind.json.JsonMapper;

/** Settings storage: defaults (env fallback), llm group encryption/masking, tool toggles. */
class ModuleSettingsServiceTest {

  private static final String CRYPTO_KEY =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private ModuleSettingRepository repo;
  private TalentHubProperties props;
  private ModuleSettingsService service;

  @BeforeEach
  void setUp() {
    repo = mock(ModuleSettingRepository.class);
    props = new TalentHubProperties();
    props.setCryptoKey(CRYPTO_KEY);
    props.getLlm().setBaseUrl("https://env-llm.example/v1");
    props.getLlm().setApiKey("env-key");
    props.getLlm().setModel("env-model");
    service = new ModuleSettingsService(repo, new CryptoService(props), props, new JsonMapper());
    // Stateful in-memory repo so update-then-read round-trips like the real DB.
    java.util.Map<String, ModuleSettingEntity> store =
        new java.util.concurrent.ConcurrentHashMap<>();
    when(repo.save(any(ModuleSettingEntity.class)))
        .thenAnswer(
            inv -> {
              ModuleSettingEntity e = inv.getArgument(0);
              store.put(e.getKey(), e);
              return e;
            });
    when(repo.findByKey(any()))
        .thenAnswer(inv -> Optional.ofNullable(store.get((String) inv.getArgument(0))));
  }

  @Test
  void emptyStateFallsBackToEnvLlmAndAllToolsEnabled() {
    assertThat(service.effectiveLlm().baseUrl()).isEqualTo("https://env-llm.example/v1");
    assertThat(service.effectiveLlm().isConfigured()).isTrue();
    assertThat(service.view().llm().hasApiKey()).isFalse();
    assertThat(service.isToolEnabled("anything")).isTrue();
    // Declared tools default enabled, in declaration order (sync with AgentToolsController).
    assertThat(service.view().tools().keySet())
        .containsExactly(
            "list_rfps",
            "get_rfp",
            "create_rfp",
            "approve_rfp",
            "delete_draft_rfp",
            "list_candidate_processes",
            "search_candidates",
            "get_candidate",
            "list_clients",
            "get_client",
            "list_positions",
            "get_position",
            "list_submissions",
            "list_documents",
            "get_document",
            "list_match_runs",
            "get_match_run",
            "list_sourcing_inbox",
            "open_candidate_process",
            "advance_process_stage",
            "close_rfp",
            "assess_fit",
            "match_candidates",
            "create_match_run",
            "get_rfp_analytics");
  }

  @Test
  void llmUpdateEncryptsApiKeyAndMasksInView() {
    service.update(
        new ModuleSettingsService.SettingsUpdateRequest(
            new ModuleSettingsService.LlmUpdate("https://stored/v1", "secret", "gpt-x"),
            null,
            null,
            null));

    assertThat(service.view().llm().baseUrl()).isEqualTo("https://stored/v1");
    assertThat(service.view().llm().hasApiKey()).isTrue();
    assertThat(service.view().llm().model()).isEqualTo("gpt-x");
    assertThat(service.effectiveLlm().apiKey()).isEqualTo("secret");
  }

  @Test
  void blankApiKeyClearsStoredKeyAndFallsBackToEnv() {
    service.update(
        new ModuleSettingsService.SettingsUpdateRequest(
            new ModuleSettingsService.LlmUpdate(null, "secret", null), null, null, null));
    assertThat(service.effectiveLlm().apiKey()).isEqualTo("secret");

    service.update(
        new ModuleSettingsService.SettingsUpdateRequest(
            new ModuleSettingsService.LlmUpdate(null, "", null), null, null, null));

    assertThat(service.view().llm().hasApiKey()).isFalse();
    assertThat(service.effectiveLlm().apiKey()).isEqualTo("env-key");
  }

  @Test
  void storedLlmBeatsEnv() {
    service.update(
        new ModuleSettingsService.SettingsUpdateRequest(
            new ModuleSettingsService.LlmUpdate("https://stored/v1", "stored-key", "stored-model"),
            null,
            null,
            null));

    assertThat(service.effectiveLlm().baseUrl()).isEqualTo("https://stored/v1");
    assertThat(service.effectiveLlm().model()).isEqualTo("stored-model");
  }

  @Test
  void validateRejectsUnknownToolAndOversizedFields() {
    assertThat(
            service.validate(
                new ModuleSettingsService.SettingsUpdateRequest(
                    null,
                    null,
                    null,
                    java.util.Map.of(
                        "nope", new ModuleSettingsService.ConnectorUpsert(null, null)))))
        .isNotNull();
    assertThat(
            service.validate(
                new ModuleSettingsService.SettingsUpdateRequest(
                    new ModuleSettingsService.LlmUpdate("https://x/" + "a".repeat(600), null, null),
                    null,
                    null,
                    null)))
        .isNotNull();
    assertThat(
            service.validate(
                new ModuleSettingsService.SettingsUpdateRequest(
                    new ModuleSettingsService.LlmUpdate("https://x/v1", null, "m"),
                    null,
                    null,
                    null)))
        .isNull();
  }

  @Test
  void corruptedStoredGroupFallsBackToDefaults() {
    ModuleSettingEntity row = new ModuleSettingEntity();
    row.setKey(ModuleSettingsService.GROUP_LLM);
    row.setValue("not json");
    when(repo.findByKey(ModuleSettingsService.GROUP_LLM)).thenReturn(Optional.of(row));

    assertThat(service.effectiveLlm().baseUrl()).isEqualTo("https://env-llm.example/v1");
  }
}
