package com.crosshubber.solutions.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.crosshubber.solutions.config.SolutionsProperties;

import tools.jackson.databind.json.JsonMapper;

/** Settings storage: defaults, encryption round-trip, tool toggles, validation. */
class ModuleSettingsServiceTest {

  private ModuleSettingRepository repo;
  private ModuleSettingsService service;
  private CryptoService crypto;

  @BeforeEach
  void setUp() {
    repo = mock(ModuleSettingRepository.class);
    crypto = new CryptoService(new SolutionsProperties());
    service = new ModuleSettingsService(repo, crypto, new SolutionsProperties(), new JsonMapper());
    // Stateful in-memory repo: save() stores by key, findByKey() reads it back —
    // so update-then-read round-trips like the real DB.
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

  private void stored(String key, Object value) {
    ModuleSettingEntity row = new ModuleSettingEntity();
    row.setKey(key);
    row.setValue(new JsonMapper().writeValueAsString(value));
    when(repo.findByKey(key)).thenReturn(Optional.of(row));
  }

  @Test
  void defaultsServeAllToolsEnabledAndAgentEnabled() {
    assertThat(service.agentEnabled()).isTrue();
    assertThat(service.systemPrompt()).isEqualTo(ModuleSettingsService.DEFAULT_SYSTEM_PROMPT);
    assertThat(service.isToolEnabled("list_projects")).isTrue();
    assertThat(service.rag().maxDocsPerQuery()).isEqualTo(3);
    assertThat(service.rag().truncationBudget()).isEqualTo(2000);
  }

  @Test
  void apiKeyEncryptsAtRestAndDecryptsForUse() {
    service.update(
        new ModuleSettingsService.SettingsUpdateRequest(
            new ModuleSettingsService.AgentUpdate(
                null, null, "http://llm.local/v1", "sk-secret", "gpt-x"),
            null,
            null));

    String encrypted = service.agent().providerApiKeyEnc();
    assertThat(encrypted).isNotNull().doesNotContain("sk-secret");
    ModuleSettingsService.EffectiveLlm llm = service.effectiveLlm();
    assertThat(llm.apiKey()).isEqualTo("sk-secret");
    assertThat(llm.baseUrl()).isEqualTo("http://llm.local/v1");
    assertThat(llm.model()).isEqualTo("gpt-x");
    assertThat(llm.isConfigured()).isTrue();
  }

  @Test
  void viewMasksApiKey() {
    service.update(
        new ModuleSettingsService.SettingsUpdateRequest(
            new ModuleSettingsService.AgentUpdate(null, null, null, "sk-long-secret-value", null),
            null,
            null));

    ModuleSettingsService.SettingsView view = service.view();
    assertThat(view.agent().hasApiKey()).isTrue();
    assertThat(String.valueOf(view.agent().providerModel())).doesNotContain("sk-");
  }

  @Test
  void updateKeepsApiKeyWhenFieldOmitted() {
    service.update(
        new ModuleSettingsService.SettingsUpdateRequest(
            new ModuleSettingsService.AgentUpdate(null, null, null, "sk-keep-me", null),
            null,
            null));
    service.update(
        new ModuleSettingsService.SettingsUpdateRequest(
            new ModuleSettingsService.AgentUpdate(null, "custom prompt", null, null, null),
            null,
            null));

    assertThat(service.effectiveLlm().apiKey()).isEqualTo("sk-keep-me");
    assertThat(service.systemPrompt()).isEqualTo("custom prompt");
  }

  @Test
  void toolToggleDisablesTool() {
    service.update(
        new ModuleSettingsService.SettingsUpdateRequest(
            null, java.util.Map.of("list_projects", false), null));

    assertThat(service.isToolEnabled("list_projects")).isFalse();
    assertThat(service.isToolEnabled("get_project")).isTrue();
    assertThat(service.view().tools().get("list_projects")).isFalse();
  }

  @Test
  void validateRejectsUnknownToolAndBadBounds() {
    assertThat(
            service.validate(
                new ModuleSettingsService.SettingsUpdateRequest(
                    null, java.util.Map.of("nope", true), null)))
        .isNotNull();
    assertThat(
            service.validate(
                new ModuleSettingsService.SettingsUpdateRequest(
                    null, null, new ModuleSettingsService.RagUpdate(99, null))))
        .isNotNull();
    assertThat(
            service.validate(
                new ModuleSettingsService.SettingsUpdateRequest(
                    new ModuleSettingsService.AgentUpdate(null, "x".repeat(4001), null, null, null),
                    null,
                    null)))
        .isNotNull();
    assertThat(
            service.validate(
                new ModuleSettingsService.SettingsUpdateRequest(
                    new ModuleSettingsService.AgentUpdate(null, "ok", null, null, null),
                    java.util.Map.of("list_projects", false),
                    new ModuleSettingsService.RagUpdate(5, 1000))))
        .isNull();
  }

  @Test
  void envFallbackAppliesWhenSettingsBlank() {
    SolutionsProperties props = new SolutionsProperties();
    props.getLlm().setBaseUrl("http://env-llm/v1");
    props.getLlm().setApiKey("env-key");
    props.getLlm().setModel("env-model");
    ModuleSettingsService envBacked =
        new ModuleSettingsService(repo, crypto, props, new JsonMapper());

    ModuleSettingsService.EffectiveLlm llm = envBacked.effectiveLlm();
    assertThat(llm.baseUrl()).isEqualTo("http://env-llm/v1");
    assertThat(llm.apiKey()).isEqualTo("env-key");
    assertThat(llm.model()).isEqualTo("env-model");

    // stored settings win over env
    stored(
        ModuleSettingsService.GROUP_AGENT,
        new ModuleSettingsService.AgentSettings(
            null, null, "http://stored/v1", null, "stored-model"));
    ModuleSettingsService withStored =
        new ModuleSettingsService(repo, crypto, props, new JsonMapper());
    ModuleSettingsService.EffectiveLlm stored = withStored.effectiveLlm();
    assertThat(stored.baseUrl()).isEqualTo("http://stored/v1");
    assertThat(stored.model()).isEqualTo("stored-model");
    assertThat(stored.apiKey()).isEqualTo("env-key");
  }

  @Test
  void corruptedStoredGroupFallsBackToDefaults() {
    ModuleSettingEntity row = new ModuleSettingEntity();
    row.setKey(ModuleSettingsService.GROUP_RAG);
    row.setValue("not json");
    when(repo.findByKey(ModuleSettingsService.GROUP_RAG)).thenReturn(Optional.of(row));

    assertThat(service.rag().maxDocsPerQuery()).isEqualTo(3);
  }
}
