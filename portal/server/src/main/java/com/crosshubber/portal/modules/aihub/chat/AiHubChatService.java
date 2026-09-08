package com.crosshubber.portal.modules.aihub.chat;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.modules.aihub.conversations.AiHubConversationsService;
import com.crosshubber.portal.modules.aihub.providers.AiHubProvidersService;
import com.crosshubber.portal.modules.settings.modules.ModuleSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import reactor.core.publisher.Flux;

/**
 * Chat orchestrator for the AI Hub module. On each user message this service:
 *
 * <ol>
 *   <li>Reads the selected provider/model/token from the {@code ai-hub} module settings (1 DB
 *       query).
 *   <li>Resolves the API key for the selected token (2 DB queries: provider + token).
 *   <li>Looks up or creates the conversation (1 DB query).
 *   <li>Builds or retrieves a cached {@link ChatClient} configured with a {@link
 *       MessageChatMemoryAdvisor} that automatically loads conversation history from and persists
 *       messages to the {@code ai_hub_chat_memory} table via {@link ChatMemory}.
 *   <li>Streams the LLM completion as SSE frames ({@code {"content":"..."}}) and appends a {@code
 *       [DONE]} sentinel.
 * </ol>
 *
 * <p>The {@link ChatClient} is cached per {@code (providerId, tokenId, model)} tuple for 60
 * seconds. Since provider tokens, base URLs, and model options only change on admin actions, a
 * short-lived cache avoids reconstructing the Spring AI client and decrypting API keys on every
 * request. Cache entries are evicted after 60s or when the maximum size (64) is reached.
 *
 * <p>Spring AI does not provide built-in SSE framing or controller utilities — the {@code Flux}
 * return type combined with {@code produces = TEXT_EVENT_STREAM_VALUE} on the controller causes
 * Spring WebFlux to emit each element as an SSE {@code data:} frame automatically. The JSON
 * wrapping ({@code {"content":"..."}}) and error handling are application-level protocol handled
 * here.
 */
@Service
public class AiHubChatService {

  private static final Logger log = LoggerFactory.getLogger(AiHubChatService.class);

  private static final String MODULE_KEY = "ai-hub";

  private final ModuleSettingsService moduleSettings;
  private final AiHubProvidersService providersService;
  private final AiHubConversationsService conversationsService;
  private final ChatMemory chatMemory;
  private final ObjectMapper objectMapper;

  /**
   * Short-lived cache for {@link ChatClient} instances. Keyed by the model selection triple
   * (provider + token + model). Avoids repeated API key decryption and model construction on
   * consecutive requests to the same model. Entries expire after 60 seconds — stale entries are
   * safe because the next admin settings change will create a new entry with the updated
   * configuration.
   */
  private final Cache<ChatClientKey, ChatClient> clientCache =
      Caffeine.newBuilder().expireAfterWrite(60, TimeUnit.SECONDS).maximumSize(64).build();

  public AiHubChatService(
      ModuleSettingsService moduleSettings,
      AiHubProvidersService providersService,
      AiHubConversationsService conversationsService,
      ChatMemory chatMemory,
      ObjectMapper objectMapper) {
    this.moduleSettings = moduleSettings;
    this.providersService = providersService;
    this.conversationsService = conversationsService;
    this.chatMemory = chatMemory;
    this.objectMapper = objectMapper;
  }

  /** Cache key uniquely identifying a ChatClient configuration. */
  record ChatClientKey(String providerId, String tokenId, String model) {}

  record EffectiveConfig(
      String providerId,
      String model,
      String tokenId,
      String systemPrompt,
      Double temperature,
      Integer maxTokens) {}

  /** Stream frames plus the conversation the exchange belongs to. */
  public record ChatStream(String conversationId, Flux<String> frames) {}

  /**
   * Entry point for a chat message. Returns a {@link ChatStream} containing the conversation ID and
   * a {@link Flux} of SSE frames. The caller (controller) returns this as {@code
   * TEXT_EVENT_STREAM_VALUE} so each element becomes an SSE {@code data:} frame.
   *
   * <p>Flow: resolve config → find/create conversation → build or cache client → stream with
   * advisor-managed history → emit frames.
   */
  public ChatStream streamChat(String userId, String conversationId, String message) {
    EffectiveConfig cfg = resolveConfig();
    log.info("[ai-hub] chat request provider={} model={}", cfg.providerId(), cfg.model());

    // --- Conversation management ---
    // If a conversation ID is provided, validate ownership. Otherwise create a new conversation
    // with the first 60 characters of the message as the title.
    String convId = conversationId;
    if (convId != null) {
      if (conversationsService.getConversation(convId, userId) == null) {
        return new ChatStream(
            null,
            Flux.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found")));
      }
    } else {
      String title = message.length() > 60 ? message.substring(0, 60) : message;
      convId = conversationsService.createConversation(userId, title).id();
    }
    final String finalConvId = convId;

    // --- API key resolution ---
    // Decrypts the token's API key and pairs it with the provider's base URL. The key is used
    // both for the ChatModel constructor and (indirectly) for outgoing HTTP requests to the LLM.
    AiHubProvidersService.ResolvedKey key =
        providersService.resolveApiKey(cfg.providerId(), cfg.tokenId());
    if (key == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "provider or token is not configured");
    }

    // --- Build or retrieve cached client ---
    // The ChatClient wraps the ChatModel + MessageChatMemoryAdvisor. The advisor automatically
    // loads conversation history from SPRING_AI_CHAT_MEMORY before the prompt and persists both
    // user and assistant messages after the LLM responds.
    ChatClient client = buildClient(cfg, key);

    // --- Stream the completion ---
    // The advisor chain: MessageChatMemoryAdvisor (loads/saves history) → ChatModelStreamAdvisor
    // (calls the LLM). Each raw text delta is wrapped in {"content":"..."} JSON. On completion a
    // [DONE] sentinel is emitted. Errors are caught and emitted as {"error":"..."} frames.
    Flux<String> frames =
        client
            .prompt()
            .system(
                s -> {
                  if (cfg.systemPrompt() != null && !cfg.systemPrompt().isBlank()) {
                    s.text(cfg.systemPrompt());
                  }
                })
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, finalConvId))
            .user(message)
            .stream()
            .content()
            .doOnSubscribe(s -> log.info("[ai-hub] stream subscribed"))
            .doOnNext(content -> log.trace("[ai-hub] chunk: {}", content))
            .doOnComplete(() -> log.info("[ai-hub] stream complete"))
            .doOnError(error -> log.error("[ai-hub] stream error: {}", error.getMessage()))
            .map(this::contentFrame)
            .concatWith(Flux.just("[DONE]"))
            .onErrorResume(error -> Flux.just(errorFrame(error)));
    return new ChatStream(finalConvId, frames);
  }

  /** Wraps a text delta in the application-level JSON frame format. */
  private String contentFrame(String content) {
    try {
      return objectMapper.writeValueAsString(Map.of("content", content));
    } catch (Exception e) {
      return "{\"content\":\"\"}";
    }
  }

  /** Wraps an error in the application-level JSON frame format, sanitizing the message. */
  private String errorFrame(Throwable error) {
    String safeMsg = error.getMessage() != null ? error.getMessage() : "stream error";
    try {
      return objectMapper.writeValueAsString(Map.of("error", safeMsg));
    } catch (Exception e) {
      return "{\"error\":\"stream error\"}";
    }
  }

  /**
   * Reads the AI Hub module settings and extracts the selected model's provider/token/model IDs
   * along with generation parameters (system prompt, temperature, max tokens).
   *
   * <p>The settings JSON stores the selected model in one of two formats:
   *
   * <ul>
   *   <li>{@code defaultModel}: a map with {@code providerId}, {@code modelId}, {@code tokenId}
   *       (preferred — set by the settings UI's model picker).
   *   <li>{@code selectedModelKey}: a colon-separated string {@code providerId:modelId:tokenId}
   *       (legacy fallback).
   * </ul>
   *
   * <p>If neither is set, or the referenced provider/token is missing, a 400 error is thrown.
   * Previously this method loaded ALL providers and tokens to build option lists — the new
   * implementation reads the selection directly from the settings JSON, eliminating N+1 DB queries.
   */
  EffectiveConfig resolveConfig() {
    Map<String, Object> settings = moduleSettings.get(MODULE_KEY);

    // Extract the selected model triple from the settings JSON.
    String providerId = null;
    String modelId = null;
    String tokenId = null;

    if (settings.get("defaultModel") instanceof Map<?, ?> dm) {
      providerId = stringSetting(dm.get("providerId"));
      modelId = stringSetting(dm.get("modelId"));
      tokenId = stringSetting(dm.get("tokenId"));
    }
    if (providerId == null && settings.get("selectedModelKey") instanceof String key) {
      String[] parts = key.split(":", 3);
      if (parts.length == 3) {
        providerId = parts[0];
        modelId = parts[1];
        tokenId = parts[2];
      }
    }

    if (providerId == null || modelId == null || tokenId == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "no chat model configured on the AI Hub settings page");
    }

    return new EffectiveConfig(
        providerId,
        modelId,
        tokenId,
        stringSetting(settings.get("systemPrompt")),
        doubleSetting(settings.get("temperature"), 0.7),
        intSetting(settings.get("maxTokens"), 4096));
  }

  /**
   * Builds a {@link ChatClient} for the given configuration, using a cache to avoid repeated
   * construction. Each unique (providerId, tokenId, model) triple gets its own cached client.
   *
   * <p>The cache is safe because:
   *
   * <ul>
   *   <li>The {@link MessageChatMemoryAdvisor} is stateless — it delegates to {@link ChatMemory}
   *       per conversation ID, so sharing a client across requests is fine.
   *   <li>The {@link ChatModel} is immutable once built — its API key and options don't change.
   *   <li>Stale entries (e.g., after an admin changes a token's API key) are evicted within 60s.
   * </ul>
   */
  private ChatClient buildClient(EffectiveConfig cfg, AiHubProvidersService.ResolvedKey key) {
    ChatClientKey cacheKey = new ChatClientKey(cfg.providerId(), cfg.tokenId(), cfg.model());
    return clientCache.get(
        cacheKey,
        k -> {
          log.info(
              "[ai-hub] building new ChatClient for provider={} model={}",
              cfg.providerId(),
              cfg.model());
          return ChatClient.builder(createChatModel(cfg, key))
              .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
              .build();
        });
  }

  /**
   * Constructs a Spring AI {@link ChatModel} for the given provider. Anthropic models use {@link
   * AnthropicChatModel}; all other providers (OpenAI, OpenRouter, DeepSeek, xAI, etc.) use {@link
   * OpenAiChatModel} since they expose an OpenAI-compatible API.
   *
   * <p>The base URL is stripped of trailing version segments ({@code /v1}, {@code /v1beta}) because
   * Spring AI appends its own path suffix — without stripping, URLs like {@code
   * https://api.openai.com/v1} would produce double-versioned paths.
   */
  private ChatModel createChatModel(EffectiveConfig cfg, AiHubProvidersService.ResolvedKey key) {
    if ("anthropic".equals(cfg.providerId())) {
      return AnthropicChatModel.builder()
          .anthropicApi(
              AnthropicApi.builder()
                  .apiKey(key.apiKey())
                  .baseUrl(stripVersionSuffix(key.baseURL()))
                  .build())
          .defaultOptions(
              AnthropicChatOptions.builder()
                  .model(cfg.model())
                  .temperature(cfg.temperature())
                  .maxTokens(cfg.maxTokens())
                  .build())
          .build();
    }
    return OpenAiChatModel.builder()
        .openAiApi(
            OpenAiApi.builder()
                .apiKey(key.apiKey())
                .baseUrl(stripVersionSuffix(key.baseURL()))
                .build())
        .defaultOptions(
            OpenAiChatOptions.builder()
                .model(cfg.model())
                .temperature(cfg.temperature())
                .maxTokens(cfg.maxTokens())
                .build())
        .build();
  }

  /**
   * Strips trailing version segments like {@code /v1}, {@code /v1beta} etc. from a base URL. Spring
   * AI already appends {@code /v1/chat/completions} by default, so providers that store {@code
   * https://api.openai.com/v1} would otherwise produce a double {@code /v1/v1/}.
   */
  static String stripVersionSuffix(String url) {
    if (url == null) {
      return null;
    }
    return url.replaceAll("/+v\\d[\\w.-]*/?$", "");
  }

  private static String stringSetting(Object value) {
    return value instanceof String s && !s.isBlank() ? s : null;
  }

  private static Double doubleSetting(Object value, double fallback) {
    return value instanceof Number n ? n.doubleValue() : fallback;
  }

  private static Integer intSetting(Object value, int fallback) {
    return value instanceof Number n ? n.intValue() : fallback;
  }
}
