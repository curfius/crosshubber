package com.crosshubber.portal.modules.aihub.chat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.modules.agent.AgentToolCallbacks;
import com.crosshubber.portal.modules.agent.ToolDispatcher;
import com.crosshubber.portal.modules.agent.ToolRegistry;
import com.crosshubber.portal.modules.aihub.context.AgentPromptAssembler;
import com.crosshubber.portal.modules.aihub.context.ClientContext;
import com.crosshubber.portal.modules.aihub.context.SessionContextBuilder;
import com.crosshubber.portal.modules.aihub.context.SessionContextPack;
import com.crosshubber.portal.modules.aihub.conversations.AiHubConversationsService;
import com.crosshubber.portal.modules.aihub.dto.ChatStreamRequest;
import com.crosshubber.portal.modules.aihub.providers.AiHubProvidersService;
import com.crosshubber.portal.modules.settings.modules.ModuleSettingsService;
import com.crosshubber.portal.security.PortalUser;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Chat orchestrator for the AI Hub module — the portal agent loop (AI plan A3/B3). On each user
 * message this service:
 *
 * <ol>
 *   <li>Reads the selected provider/model/token from the {@code ai-hub} module settings (1 DB
 *       query) plus the {@code agent.enabled} flag (default on).
 *   <li>Builds the {@link SessionContextPack} — server-authoritative identity + client-supplied
 *       navigation state — and assembles the agent system message (persona, configured prompt,
 *       context JSON, tool catalogue).
 *   <li>Executes a pending tool confirmation, when the request carries one, folding the result into
 *       the turn (AI plan B6).
 *   <li>Looks up or creates the conversation; builds or retrieves a cached {@link ChatClient}
 *       configured with a {@link MessageChatMemoryAdvisor} ({@code ai_hub_chat_memory}).
 *   <li>Streams the completion as SSE frames: {@code {"content":"..."}} deltas, typed {@code
 *       tool_call}/{@code tool_result}/{@code confirmation_required} frames emitted by the tool
 *       loop (see {@code docs/agent-protocol.md}), {@code {"error":"..."}} and the {@code [DONE]}
 *       sentinel.
 * </ol>
 *
 * <p>The {@link ChatClient} is cached per {@code (providerId, tokenId, model)} tuple for 60
 * seconds. Tool callbacks are request-scoped: they carry the caller's identity, a per-turn
 * iteration budget and the frame emitter — never shared across requests.
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
  private final SessionContextBuilder contextBuilder;
  private final AgentPromptAssembler promptAssembler;
  private final ToolRegistry toolRegistry;
  private final AgentToolCallbacks toolCallbacks;
  private final ToolDispatcher toolDispatcher;

  /**
   * Short-lived cache for {@link ChatClient} instances. Keyed by the model selection triple
   * (provider + token + model). Avoids repeated API key decryption and model construction on
   * consecutive requests to the same model. Entries expire after 60s — stale entries are safe
   * because the next admin settings change will create a new entry with the updated configuration.
   */
  private final Cache<ChatClientKey, ChatClient> clientCache =
      Caffeine.newBuilder().expireAfterWrite(60, TimeUnit.SECONDS).maximumSize(64).build();

  public AiHubChatService(
      ModuleSettingsService moduleSettings,
      AiHubProvidersService providersService,
      AiHubConversationsService conversationsService,
      ChatMemory chatMemory,
      ObjectMapper objectMapper,
      SessionContextBuilder contextBuilder,
      AgentPromptAssembler promptAssembler,
      ToolRegistry toolRegistry,
      AgentToolCallbacks toolCallbacks,
      ToolDispatcher toolDispatcher) {
    this.moduleSettings = moduleSettings;
    this.providersService = providersService;
    this.conversationsService = conversationsService;
    this.chatMemory = chatMemory;
    this.objectMapper = objectMapper;
    this.contextBuilder = contextBuilder;
    this.promptAssembler = promptAssembler;
    this.toolRegistry = toolRegistry;
    this.toolCallbacks = toolCallbacks;
    this.toolDispatcher = toolDispatcher;
  }

  /** Cache key uniquely identifying a ChatClient configuration. */
  record ChatClientKey(String providerId, String tokenId, String model) {}

  record EffectiveConfig(
      String providerId,
      String model,
      String tokenId,
      String systemPrompt,
      Double temperature,
      Integer maxTokens,
      boolean agentEnabled) {}

  /** Stream frames plus the conversation the exchange belongs to. */
  public record ChatStream(String conversationId, Flux<String> frames) {}

  /**
   * Entry point for a chat message. Returns a {@link ChatStream} containing the conversation ID and
   * a {@link Flux} of SSE frames. The caller (controller) returns this as {@code
   * TEXT_EVENT_STREAM_VALUE} so each element becomes an SSE {@code data:} frame.
   */
  public ChatStream streamChat(
      PortalUser user,
      String conversationId,
      String message,
      ClientContext context,
      ChatStreamRequest.ToolConfirmation confirmation) {
    EffectiveConfig cfg = resolveConfig();
    log.info("[ai-hub] chat request provider={} model={}", cfg.providerId(), cfg.model());

    // --- Conversation management ---
    // If a conversation ID is provided, validate ownership. Otherwise create a new conversation
    // with the first 60 characters of the message as the title.
    String convId = conversationId;
    if (convId != null) {
      if (conversationsService.getConversation(convId, user.sub()) == null) {
        return new ChatStream(
            null,
            Flux.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found")));
      }
    } else {
      String title = message.length() > 60 ? message.substring(0, 60) : message;
      convId = conversationsService.createConversation(user.sub(), title).id();
    }
    final String finalConvId = convId;

    // --- API key resolution ---
    // Decrypts the token's API key and pairs it with the provider's base URL.
    AiHubProvidersService.ResolvedKey key =
        providersService.resolveApiKey(cfg.providerId(), cfg.tokenId());
    if (key == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "provider or token is not configured");
    }

    // --- Session context + agent system prompt (AI plan A3) ---
    SessionContextPack pack = contextBuilder.build(user, context);
    List<AgentPromptAssembler.ToolSummary> catalogue =
        cfg.agentEnabled() ? toolSummaries() : List.of();
    String systemPrompt = promptAssembler.build(cfg.systemPrompt(), pack, catalogue);

    // --- Request-scoped tool loop wiring (AI plan B3) ---
    // Frames from the tool loop ride the same SSE channel as content deltas: a request-scoped
    // unicast sink merges into the output flux; the sink completes when the content flux does.
    Sinks.Many<String> toolFrames = Sinks.many().unicast().onBackpressureBuffer();
    List<ToolCallback> callbacks =
        cfg.agentEnabled()
            ? toolCallbacks.forRequest(user, finalConvId, toolFrames::tryEmitNext)
            : List.of();

    // --- Pending tool confirmation (AI plan B6) ---
    // Executed before the model runs; the result is folded into the user message so the model can
    // narrate the outcome and the transcript records the confirmation.
    String userMessage = message;
    if (confirmation != null && confirmation.callId() != null) {
      userMessage =
          withConfirmedToolResult(
              user, confirmation.callId(), userMessage, toolFrames::tryEmitNext);
    }

    // --- Build or retrieve cached client ---
    ChatClient client = buildClient(cfg, key);

    // --- Stream the completion ---
    Flux<String> content =
        client
            .prompt()
            .system(systemPrompt)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, finalConvId))
            .user(userMessage)
            .tools(callbacks.isEmpty() ? new Object[0] : callbacks.toArray())
            .stream()
            .content()
            .doOnSubscribe(s -> log.info("[ai-hub] stream subscribed"))
            .doOnNext(c -> log.trace("[ai-hub] chunk: {}", c))
            .doOnComplete(() -> log.info("[ai-hub] stream complete"))
            .doOnError(error -> log.error("[ai-hub] stream error: {}", error.getMessage()))
            .map(this::contentFrame)
            .doFinally(signal -> toolFrames.tryEmitComplete());
    Flux<String> frames =
        Flux.merge(content, toolFrames.asFlux())
            .concatWith(Flux.just("[DONE]"))
            .onErrorResume(error -> Flux.just(errorFrame(error)));
    return new ChatStream(finalConvId, frames);
  }

  /** Executes a confirmed pending tool call and folds a system note into the user message. */
  String withConfirmedToolResult(
      PortalUser user, String callId, String message, java.util.function.Consumer<String> emit) {
    ToolDispatcher.ToolResult result = toolDispatcher.executePending(callId, user);
    if (emit != null) {
      ObjectNode frame = objectMapper.createObjectNode();
      frame.put("type", "tool_result");
      frame.put("tool", "confirmed:" + callId);
      frame.put("status", result.status());
      emit.accept(objectMapper.writeValueAsString(frame));
    }
    return message
        + "\n\n[System note: the user confirmed the pending tool call "
        + callId
        + ". It executed with status "
        + result.status()
        + " and result: "
        + result.payload()
        + ". Summarize the outcome for the user.]";
  }

  private List<AgentPromptAssembler.ToolSummary> toolSummaries() {
    return toolRegistry.catalogue().stream()
        .map(t -> new AgentPromptAssembler.ToolSummary(t.modelName(), t.description(), t.mutates()))
        .toList();
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
   * along with generation parameters (system prompt, temperature, max tokens) and the {@code
   * agent.enabled} flag (default on).
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
        intSetting(settings.get("maxTokens"), 4096),
        booleanSetting(settings.get("agent.enabled"), true));
  }

  /**
   * Builds a {@link ChatClient} for the given configuration, using a cache to avoid repeated
   * construction. Each unique (providerId, tokenId, model) triple gets its own cached client.
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
   */
  ChatModel createChatModel(EffectiveConfig cfg, AiHubProvidersService.ResolvedKey key) {
    if ("anthropic".equals(cfg.providerId())) {
      return AnthropicChatModel.builder()
          .options(
              AnthropicChatOptions.builder()
                  .apiKey(key.apiKey())
                  .baseUrl(key.baseURL())
                  .model(com.anthropic.models.messages.Model.of(cfg.model()))
                  .temperature(cfg.temperature())
                  .maxTokens(cfg.maxTokens())
                  .build())
          .build();
    }
    return OpenAiChatModel.builder()
        .options(
            OpenAiChatOptions.builder()
                .apiKey(key.apiKey())
                .baseUrl(key.baseURL())
                .model(cfg.model())
                .temperature(cfg.temperature())
                .maxTokens(cfg.maxTokens())
                .build())
        .build();
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

  private static boolean booleanSetting(Object value, boolean fallback) {
    return value instanceof Boolean b ? b : fallback;
  }
}
