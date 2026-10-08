package com.crosshubber.portal.modules.aihub.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.config.JacksonConfig;
import com.crosshubber.portal.modules.aihub.agent.AgentToolCallbacks;
import com.crosshubber.portal.modules.aihub.agent.ToolDispatcher;
import com.crosshubber.portal.modules.aihub.agent.ToolRegistry;
import com.crosshubber.portal.modules.aihub.context.AgentPromptAssembler;
import com.crosshubber.portal.modules.aihub.context.SessionContextBuilder;
import com.crosshubber.portal.modules.aihub.conversations.AiHubConversationsService;
import com.crosshubber.portal.modules.aihub.providers.AiHubProvidersService;
import com.crosshubber.portal.modules.aihub.settings.AiHubSettingsService;
import com.crosshubber.portal.modules.usersettings.scopes.UserSettingsService;
import com.crosshubber.portal.security.PortalUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AiHubChatServiceTest {

  // --- createChatModel() — Spring AI 2.0 provider branching (client construction is offline) ---

  @Test
  void createChatModelBuildsAnthropicModelForAnthropicProvider() {
    AiHubProvidersService providers = mock(AiHubProvidersService.class);
    JsonMapper mapper = new JacksonConfig().jsonMapper();
    AiHubChatService svc =
        new AiHubChatService(
            mock(AiHubSettingsService.class),
            providers,
            mock(AiHubConversationsService.class),
            mock(UserSettingsService.class),
            mock(ChatMemory.class),
            mapper,
            mock(SessionContextBuilder.class),
            mock(AgentPromptAssembler.class),
            mock(ToolRegistry.class),
            mock(AgentToolCallbacks.class),
            mock(ToolDispatcher.class));

    AiHubChatService.EffectiveConfig cfg =
        new AiHubChatService.EffectiveConfig(
            "anthropic", "claude-sonnet-4", "t1", null, 0.7, 4096, true);
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
            mock(AiHubSettingsService.class),
            providers,
            mock(AiHubConversationsService.class),
            mock(UserSettingsService.class),
            mock(ChatMemory.class),
            mapper,
            mock(SessionContextBuilder.class),
            mock(AgentPromptAssembler.class),
            mock(ToolRegistry.class),
            mock(AgentToolCallbacks.class),
            mock(ToolDispatcher.class));

    // OpenAI-compatible base URLs keep their version segment: the openai-java SDK
    // appends /chat/completions to the version-scoped root itself.
    for (String base :
        List.of(
            "https://api.openai.com/v1",
            "https://openrouter.ai/api/v1",
            "https://api.x.ai/v1",
            "https://api.deepseek.com")) {
      AiHubChatService.EffectiveConfig cfg =
          new AiHubChatService.EffectiveConfig("openai", "gpt-4o", "t1", null, 0.7, 4096, true);
      ChatModel model =
          svc.createChatModel(cfg, new AiHubProvidersService.ResolvedKey("sk-test", base));
      assertEquals(OpenAiChatModel.class, model.getClass(), "base URL: " + base);
    }
  }

  // --- resolveConfig() ---

  private AiHubChatService newService(Map<String, Object> settings) {
    return newService(settings, mock(ToolDispatcher.class));
  }

  private AiHubChatService newService(Map<String, Object> settings, ToolDispatcher dispatcher) {
    AiHubSettingsService aiHubSettings = mock(AiHubSettingsService.class);
    when(aiHubSettings.get()).thenReturn(settings);
    JsonMapper mapper = new JacksonConfig().jsonMapper();
    return new AiHubChatService(
        aiHubSettings,
        mock(AiHubProvidersService.class),
        mock(AiHubConversationsService.class),
        mock(UserSettingsService.class),
        mock(ChatMemory.class),
        mapper,
        mock(SessionContextBuilder.class),
        mock(AgentPromptAssembler.class),
        mock(ToolRegistry.class),
        mock(AgentToolCallbacks.class),
        dispatcher);
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
    assertEquals(true, cfg.agentEnabled(), "agent loop is enabled by default");
  }

  @Test
  void resolveConfigHonorsAgentEnabledFlag() {
    Map<String, Object> settings =
        Map.of(
            "defaultModel",
            Map.of("providerId", "openai", "modelId", "gpt-4o", "tokenId", "t"),
            "agent.enabled",
            false);

    AiHubChatService.EffectiveConfig cfg = newService(settings).resolveConfig();

    assertEquals(false, cfg.agentEnabled());
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

  // --- per-user overrides (phase 5) ---

  /** Service with both settings and a stubbed provider resolver for user-override tests. */
  private AiHubChatService newServiceWithResolver(
      Map<String, Object> settings, AiHubProvidersService providers) {
    AiHubSettingsService aiHubSettings = mock(AiHubSettingsService.class);
    when(aiHubSettings.get()).thenReturn(settings);
    JsonMapper mapper = new JacksonConfig().jsonMapper();
    return new AiHubChatService(
        aiHubSettings,
        providers,
        mock(AiHubConversationsService.class),
        mock(UserSettingsService.class),
        mock(ChatMemory.class),
        mapper,
        mock(SessionContextBuilder.class),
        mock(AgentPromptAssembler.class),
        mock(ToolRegistry.class),
        mock(AgentToolCallbacks.class),
        mock(ToolDispatcher.class));
  }

  @Test
  void userDefaultModelOverridesTenantDefault() {
    AiHubProvidersService providers = mock(AiHubProvidersService.class);
    when(providers.resolveApiKey("anthropic", "tok9"))
        .thenReturn(new AiHubProvidersService.ResolvedKey("sk-x", "https://api.anthropic.com"));
    AiHubChatService svc =
        newServiceWithResolver(Map.of("selectedModelKey", "openai:gpt-4o:t1"), providers);

    AiHubChatService.EffectiveConfig cfg =
        svc.resolveConfig(
            Map.of("selectedModelKey", "openai:gpt-4o:t1"),
            Map.of(
                "defaultModel",
                Map.of("providerId", "anthropic", "modelId", "claude-3", "tokenId", "tok9")));

    assertEquals("anthropic", cfg.providerId());
    assertEquals("claude-3", cfg.model());
    assertEquals("tok9", cfg.tokenId());
  }

  @Test
  void userDefaultModelFallsBackWhenTokenNotSelectable() {
    AiHubProvidersService providers = mock(AiHubProvidersService.class);
    when(providers.resolveApiKey(anyString(), anyString())).thenReturn(null);
    AiHubChatService svc =
        newServiceWithResolver(
            Map.of("selectedModelKey", "openai:gpt-4o:t1", "selectedTokens", List.of("t1")),
            providers);

    AiHubChatService.EffectiveConfig cfg =
        svc.resolveConfig(
            Map.of("selectedModelKey", "openai:gpt-4o:t1", "selectedTokens", List.of("t1")),
            Map.of(
                "defaultModel",
                Map.of("providerId", "openai", "modelId", "gpt-4o-mini", "tokenId", "t2")));

    assertEquals(
        "t1", cfg.tokenId(), "tenant default survives when the user pick is not selectable");
  }

  @Test
  void userDefaultModelFallsBackWhenProviderUnresolvable() {
    AiHubProvidersService providers = mock(AiHubProvidersService.class);
    when(providers.resolveApiKey("gone", "t9")).thenReturn(null);
    AiHubChatService svc =
        newServiceWithResolver(Map.of("selectedModelKey", "openai:gpt-4o:t1"), providers);

    AiHubChatService.EffectiveConfig cfg =
        svc.resolveConfig(
            Map.of("selectedModelKey", "openai:gpt-4o:t1"),
            Map.of("defaultModel", Map.of("providerId", "gone", "modelId", "m", "tokenId", "t9")));

    assertEquals("openai", cfg.providerId());
    assertEquals("t1", cfg.tokenId());
  }

  @Test
  void disabledToolNamesParsesUserListAndIgnoresGarbage() {
    assertEquals(
        Set.of("solutions_search"),
        AiHubChatService.disabledToolNames(
            Map.of("disabledTools", java.util.Arrays.asList("solutions_search", 42, null))));
    assertEquals(Set.of(), AiHubChatService.disabledToolNames(Map.of()));
    assertEquals(Set.of(), AiHubChatService.disabledToolNames(Map.of("disabledTools", "nope")));
  }

  // --- withConfirmedToolResult() (AI plan B6) ---

  @Test
  void withConfirmedToolResultExecutesPendingCallAndEmitsToolResultFrame() {
    PortalUser user = new PortalUser("u1", "Dev", "dev@example.com", List.of("solutions-user"));
    JsonMapper mapper = new JacksonConfig().jsonMapper();
    ObjectNode payload = mapper.createObjectNode().put("runId", "run_1");
    ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    when(dispatcher.executePending("call_9", user))
        .thenReturn(ToolDispatcher.ToolResult.of("ok", payload));
    AiHubChatService svc = newService(Map.of(), dispatcher);

    List<String> frames = new ArrayList<>();
    String message = svc.withConfirmedToolResult(user, "call_9", "create the run", frames::add);

    assertTrue(message.startsWith("create the run"), "original user message comes first");
    assertTrue(message.contains("call_9"));
    assertTrue(message.contains("status ok"));
    assertTrue(message.contains("run_1"));

    assertEquals(1, frames.size());
    JsonNode frame = mapper.readTree(frames.get(0));
    assertEquals("tool_result", frame.path("type").asString());
    assertEquals("confirmed:call_9", frame.path("tool").asString());
    assertEquals("ok", frame.path("status").asString());
  }

  @Test
  void withConfirmedToolResultFoldsExpiredConfirmationIntoMessage() {
    PortalUser user = new PortalUser("u1", "Dev", "dev@example.com", List.of("solutions-user"));
    JsonMapper mapper = new JacksonConfig().jsonMapper();
    ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    when(dispatcher.executePending("call_gone", user))
        .thenReturn(
            ToolDispatcher.ToolResult.of(
                "error",
                mapper.createObjectNode().put("error", "confirmation expired or unknown call id")));
    AiHubChatService svc = newService(Map.of(), dispatcher);

    List<String> frames = new ArrayList<>();
    String message = svc.withConfirmedToolResult(user, "call_gone", "ok?", frames::add);

    assertTrue(message.contains("status error"));
    JsonNode frame = mapper.readTree(frames.get(0));
    assertEquals("error", frame.path("status").asString());
    assertEquals("confirmed:call_gone", frame.path("tool").asString());
  }
}
