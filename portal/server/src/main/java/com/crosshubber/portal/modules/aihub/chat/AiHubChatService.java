package com.crosshubber.portal.modules.aihub.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.crosshubber.portal.modules.aihub.ChatMessage;
import com.crosshubber.portal.modules.aihub.dto.ProviderDto;
import com.crosshubber.portal.modules.aihub.dto.TokenDto;
import com.crosshubber.portal.modules.aihub.providers.AiHubProvidersService;
import com.crosshubber.portal.modules.aihub.conversations.AiHubConversationsService;
import com.crosshubber.portal.modules.settings.modules.ModuleSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Single chat orchestrator: resolves provider/model/token and generation
 * parameters from the {@code ai-hub} module settings (the AI Hub settings
 * page), builds the Spring AI client, streams the completion and persists
 * the conversation server-side.
 */
@Service
public class AiHubChatService {

  private static final Logger log = LoggerFactory.getLogger(AiHubChatService.class);

  private static final String MODULE_KEY = "ai-hub";
  static final int HISTORY_LIMIT = 20;

  private final ModuleSettingsService moduleSettings;
  private final AiHubProvidersService providersService;
  private final AiHubConversationsService conversationsService;
  private final ObjectMapper objectMapper;

  public AiHubChatService(
      ModuleSettingsService moduleSettings,
      AiHubProvidersService providersService,
      AiHubConversationsService conversationsService,
      ObjectMapper objectMapper) {
    this.moduleSettings = moduleSettings;
    this.providersService = providersService;
    this.conversationsService = conversationsService;
    this.objectMapper = objectMapper;
  }

  record ChatOption(String providerId, String modelId, String tokenId) {}

  record EffectiveConfig(
      String providerId, String model, String tokenId, String systemPrompt,
      Double temperature, Integer maxTokens) {}

  /** Stream frames plus the conversation the exchange belongs to. */
  public record ChatStream(String conversationId, Flux<String> frames) {}

  public ChatStream streamChat(String userId, String conversationId, String message) {
    EffectiveConfig cfg = resolveConfig();
    log.info("[ai-hub] chat request provider={} model={}", cfg.providerId(), cfg.model());

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
    conversationsService.addMessage(finalConvId, "user", message, cfg.providerId(), cfg.model());

    List<Message> history = new ArrayList<>();
    for (ChatMessage msg : conversationsService.getRecentMessages(finalConvId, HISTORY_LIMIT)) {
      if ("user".equals(msg.role())) {
        history.add(new UserMessage(msg.content()));
      } else if ("assistant".equals(msg.role())) {
        history.add(new AssistantMessage(msg.content()));
      }
    }

    ChatClient.ChatClientRequestSpec prompt = buildClient(cfg).prompt();
    if (cfg.systemPrompt() != null && !cfg.systemPrompt().isBlank()) {
      prompt.system(cfg.systemPrompt());
    }
    prompt.messages(history);

    StringBuilder reply = new StringBuilder();
    Flux<String> frames =
        prompt
            .stream()
            .content()
            .doOnSubscribe(s -> log.info("[ai-hub] stream subscribed"))
            .doOnNext(content -> {
              log.trace("[ai-hub] chunk: {}", content);
              reply.append(content);
            })
            .doOnComplete(() -> log.info("[ai-hub] stream complete"))
            .doOnError(error -> log.error("[ai-hub] stream error: {}", error.getMessage()))
            .map(this::contentFrame)
            .concatWith(persistAssistantReply(finalConvId, cfg, reply))
            .concatWith(Flux.just("[DONE]"))
            .onErrorResume(
                error -> Flux.just(errorFrame(error)));
    return new ChatStream(finalConvId, frames);
  }

  private Mono<String> persistAssistantReply(
      String conversationId, EffectiveConfig cfg, StringBuilder reply) {
    return Mono.<String>fromRunnable(
            () ->
                conversationsService.addMessage(
                    conversationId, "assistant", reply.toString(), cfg.providerId(), cfg.model()))
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(
            e -> {
              log.error("[ai-hub] failed to persist assistant reply", e);
              return Mono.empty();
            });
  }

  private String contentFrame(String content) {
    try {
      return objectMapper.writeValueAsString(Map.of("content", content));
    } catch (Exception e) {
      return "{\"content\":\"\"}";
    }
  }

  private String errorFrame(Throwable error) {
    String safeMsg = error.getMessage() != null ? error.getMessage() : "stream error";
    try {
      return objectMapper.writeValueAsString(Map.of("error", safeMsg));
    } catch (Exception e) {
      return "{\"error\":\"stream error\"}";
    }
  }

  EffectiveConfig resolveConfig() {
    Map<String, Object> settings = moduleSettings.get(MODULE_KEY);
    List<ChatOption> options = deriveOptions(settings);
    if (options.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "no chat model configured on the AI Hub settings page");
    }
    ChatOption selected = matchOption(settings, options);
    return new EffectiveConfig(
        selected.providerId(),
        selected.modelId(),
        selected.tokenId(),
        stringSetting(settings.get("systemPrompt")),
        doubleSetting(settings.get("temperature"), 0.7),
        intSetting(settings.get("maxTokens"), 4096));
  }

  /** Options from the settings page's selected tokens × their enabled models, in stable order. */
  private List<ChatOption> deriveOptions(Map<String, Object> settings) {
    Set<String> selectedTokens = new HashSet<>();
    if (settings.get("selectedTokens") instanceof List<?> ids) {
      for (Object id : ids) {
        if (id != null) {
          selectedTokens.add(String.valueOf(id));
        }
      }
    }
    List<ChatOption> options = new ArrayList<>();
    for (ProviderDto provider : providersService.getAll()) {
      if (!Boolean.TRUE.equals(provider.enabled())) {
        continue;
      }
      for (TokenDto token : provider.tokens()) {
        if (!Boolean.TRUE.equals(token.enabled()) || !selectedTokens.contains(token.id())) {
          continue;
        }
        for (Object raw : token.models()) {
          if (raw instanceof Map<?, ?> m
              && Boolean.TRUE.equals(toBoolean(m.get("enabled")))
              && m.get("id") != null && !String.valueOf(m.get("id")).isBlank()) {
            options.add(new ChatOption(provider.id(), String.valueOf(m.get("id")), token.id()));
          }
        }
      }
    }
    return options;
  }

  /** Prefers the structured {@code defaultModel}; falls back to {@code selectedModelKey}. */
  private ChatOption matchOption(Map<String, Object> settings, List<ChatOption> options) {
    if (settings.get("defaultModel") instanceof Map<?,?> dm) {
      String providerId = stringSetting(dm.get("providerId"));
      String modelId = stringSetting(dm.get("modelId"));
      String tokenId = stringSetting(dm.get("tokenId"));
      for (ChatOption o : options) {
        if (o.providerId().equals(providerId) && o.modelId().equals(modelId)
            && o.tokenId().equals(tokenId)) {
          return o;
        }
      }
    }
    if (settings.get("selectedModelKey") instanceof String key && key.contains(":")) {
      String[] parts = key.split(":", 3);
      if (parts.length == 3) {
        for (ChatOption o : options) {
          if (o.providerId().equals(parts[0]) && o.modelId().equals(parts[1])
              && o.tokenId().equals(parts[2])) {
            return o;
          }
        }
      }
    }
    return options.get(0);
  }

  private ChatClient buildClient(EffectiveConfig cfg) {
    AiHubProvidersService.ResolvedKey key =
        providersService.resolveApiKey(cfg.providerId(), cfg.tokenId());
    if (key == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "provider or token is not configured");
    }
    return ChatClient.builder(createChatModel(cfg, key)).build();
  }

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
   * Strips trailing version segments like {@code /v1}, {@code /v1beta} etc. from a base URL.
   * Spring AI already appends {@code /v1/chat/completions} by default, so providers that store
   * {@code https://api.openai.com/v1} would otherwise produce a double {@code /v1/v1/}.
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

  private static Boolean toBoolean(Object value) {
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof String s) {
      return Boolean.parseBoolean(s);
    }
    return null;
  }
}
