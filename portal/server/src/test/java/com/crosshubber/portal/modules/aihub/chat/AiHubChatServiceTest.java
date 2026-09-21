package com.crosshubber.portal.modules.aihub.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.modules.aihub.conversations.AiHubConversationsService;
import com.crosshubber.portal.modules.aihub.providers.AiHubProvidersService;
import com.crosshubber.portal.modules.settings.modules.ModuleSettingsService;

import tools.jackson.databind.json.JsonMapper;

class AiHubChatServiceTest {

  // --- createChatModel() — Spring AI 2.0 provider branching (client construction is offline) ---

  @Test
  void createChatModelBuildsAnthropicModelForAnthropicProvider() {
    AiHubProvidersService providers = mock(AiHubProvidersService.class);
    JsonMapper mapper = new JacksonConfig().jsonMapper();
    AiHubChatService svc =
        new AiHubChatService(
            mock(ModuleSettingsService.class),
            providers,
            mock(AiHubConversationsService.class),
            mock(ChatMemory.class),
            mapper);

    AiHubChatService.EffectiveConfig cfg =
        new AiHubChatService.EffectiveConfig("anthropic", "claude-sonnet-4", "t1", null, 0.7, 4096);
    ChatModel model =
        svc.createChatModel(
            cfg, new AiHubProvidersService.ResolvedKey("sk-test", "https://api.anthropic.com"));

    assertEquals(AnthropicChatModel.class, model.getClass());
  }

  @Test
  void createChatModelBuildsOpenAiModelForOpenAiCompatibleProviders() {
    AiHubProvidersService providers = mock(AiHubProvidersService.class);
    JsonMapper mapper = new JacksonConfig().jsonMapper();
    AiHubChatService svc =
        new AiHubChatService(
            mock(ModuleSettingsService.class),
            providers,
            mock(AiHubConversationsService.class),
            mock(ChatMemory.class),
            mapper);

    // OpenAI-compatible base URLs keep their version segment: the openai-java SDK
    // appends /chat/completions to the version-scoped root itself.
    for (String base :
        List.of(
            "https://api.openai.com/v1",
            "https://openrouter.ai/api/v1",
            "https://api.x.ai/v1",
            "https://api.deepseek.com")) {
      AiHubChatService.EffectiveConfig cfg =
          new AiHubChatService.EffectiveConfig("openai", "gpt-4o", "t1", null, 0.7, 4096);
      ChatModel model =
          svc.createChatModel(cfg, new AiHubProvidersService.ResolvedKey("sk-test", base));
      assertEquals(OpenAiChatModel.class, model.getClass(), "base URL: " + base);
    }
  }

  // --- resolveConfig() ---

  private AiHubChatService newService(Map<String, Object> settings) {
    ModuleSettingsService moduleSettings = mock(ModuleSettingsService.class);
    when(moduleSettings.get("ai-hub")).thenReturn(settings);
    JsonMapper mapper = new JacksonConfig().jsonMapper();
    return new AiHubChatService(
        moduleSettings,
        mock(AiHubProvidersService.class),
        mock(AiHubConversationsService.class),
        mock(ChatMemory.class),
        mapper);
  }

  @Test
  void resolveConfigReadsDefaultModelMap() {
    Map<String, Object> settings = new HashMap<>();
    settings.put(
        "defaultModel", Map.of("providerId", "openai", "modelId", "gpt-4o", "tokenId", "tok1"));
    settings.put("systemPrompt", "be helpful");
    settings.put("temperature", 0.3);
    settings.put("maxTokens", 1024);

    AiHubChatService.EffectiveConfig cfg = newService(settings).resolveConfig();

    assertEquals("openai", cfg.providerId());
    assertEquals("gpt-4o", cfg.model());
    assertEquals("tok1", cfg.tokenId());
    assertEquals("be helpful", cfg.systemPrompt());
    assertEquals(0.3, cfg.temperature());
    assertEquals(1024, cfg.maxTokens());
  }

  @Test
  void resolveConfigFallsBackToLegacySelectedModelKey() {
    Map<String, Object> settings = Map.of("selectedModelKey", "anthropic:claude-3:tok9");

    AiHubChatService.EffectiveConfig cfg = newService(settings).resolveConfig();

    assertEquals("anthropic", cfg.providerId());
    assertEquals("claude-3", cfg.model());
    assertEquals("tok9", cfg.tokenId());
    assertNull(cfg.systemPrompt());
  }

  @Test
  void resolveConfigAppliesDefaultsForGenerationParams() {
    Map<String, Object> settings =
        Map.of("defaultModel", Map.of("providerId", "openai", "modelId", "gpt-4o", "tokenId", "t"));

    AiHubChatService.EffectiveConfig cfg = newService(settings).resolveConfig();

    assertEquals(0.7, cfg.temperature());
    assertEquals(4096, cfg.maxTokens());
  }

  @Test
  void resolveConfigRejectsMissingModelSelection() {
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> newService(Map.of()).resolveConfig());
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }

  @Test
  void resolveConfigRejectsMalformedLegacyKey() {
    Map<String, Object> settings = Map.of("selectedModelKey", "only-two:parts");

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> newService(settings).resolveConfig());
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }
}
