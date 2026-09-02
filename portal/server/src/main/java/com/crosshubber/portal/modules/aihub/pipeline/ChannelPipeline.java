package com.crosshubber.portal.modules.aihub.pipeline;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.crosshubber.portal.modules.aihub.channels.AiHubChannelEntity;
import com.crosshubber.portal.modules.aihub.channels.AiHubChannelsService;
import com.crosshubber.portal.modules.aihub.channels.dto.ChannelCredentials;
import com.crosshubber.portal.modules.aihub.chat.ChatCompletionService;
import com.crosshubber.portal.modules.aihub.conversations.AiHubConversationsService;
import com.crosshubber.portal.modules.aihub.providers.AiHubProviderEntity;
import com.crosshubber.portal.modules.aihub.providers.AiHubProviderRepository;
import com.crosshubber.portal.modules.aihub.providers.AiHubProvidersService;
import com.crosshubber.portal.modules.aihub.telegram.TelegramClient;
import com.crosshubber.portal.modules.aihub.whatsapp.WhatsappClient;
import com.crosshubber.portal.modules.settings.modules.ModuleSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Inbound channel pipeline — mirrors {@code portal/src/modules/ai-hub/channels/pipeline.ts}: dedupe
 * → conversation resolution → history → LLM → reply → persistence. Never throws: failures are
 * recorded on the channel status (and optionally echoed to the user).
 */
@Service
public class ChannelPipeline {

  private static final Logger log = LoggerFactory.getLogger(ChannelPipeline.class);

  public static final int HISTORY_DEFAULT_LIMIT = 20;
  private static final int COMPLETION_TIMEOUT_MS = 60_000;

  private final AiHubChannelsService channelsService;
  private final AiHubConversationsService conversationsService;
  private final AiHubProvidersService providersService;
  private final AiHubProviderRepository providerRepo;
  private final ModuleSettingsService moduleSettingsService;
  private final ChatCompletionService chatService;
  private final TelegramClient telegramClient;
  private final WhatsappClient whatsappClient;
  private final ObjectMapper objectMapper;

  public ChannelPipeline(
      AiHubChannelsService channelsService,
      AiHubConversationsService conversationsService,
      AiHubProvidersService providersService,
      AiHubProviderRepository providerRepo,
      ModuleSettingsService moduleSettingsService,
      ChatCompletionService chatService,
      TelegramClient telegramClient,
      WhatsappClient whatsappClient,
      ObjectMapper objectMapper) {
    this.channelsService = channelsService;
    this.conversationsService = conversationsService;
    this.providersService = providersService;
    this.providerRepo = providerRepo;
    this.moduleSettingsService = moduleSettingsService;
    this.chatService = chatService;
    this.telegramClient = telegramClient;
    this.whatsappClient = whatsappClient;
    this.objectMapper = objectMapper;
  }

  public record InboundMessage(
      String externalChatId, String externalMessageId, String text, String userName) {}

  public record ModelBinding(
      String providerId, String model, String tokenId, String apiKey, String baseURL) {}

  /** Resolves the model binding: explicit channel config, else first enabled combo. */
  public ModelBinding resolveModelBinding(Map<String, Object> cfg) {
    String cfgProvider = cfg.get("providerId") instanceof String s && !s.isBlank() ? s : null;
    String cfgModel = cfg.get("model") instanceof String s && !s.isBlank() ? s : null;
    String cfgToken = cfg.get("tokenId") instanceof String s && !s.isBlank() ? s : null;
    if (cfgProvider != null && cfgModel != null) {
      AiHubProvidersService.ResolvedKey resolved =
          providersService.resolveApiKey(cfgProvider, cfgToken);
      if (resolved != null) {
        return new ModelBinding(
            cfgProvider, cfgModel, cfgToken, resolved.apiKey(), resolved.baseURL());
      }
    }
    for (AiHubProviderEntity p : providerRepo.findAll()) {
      if (!Boolean.TRUE.equals(p.getEnabled()) || p.getBaseUrl() == null) {
        continue;
      }
      for (var t : providersService.tokenEntities(p.getId())) {
        if (!Boolean.TRUE.equals(t.getEnabled())) {
          continue;
        }
        String model = firstEnabledModel(t.getModels());
        if (model == null) {
          continue;
        }
        AiHubProvidersService.ResolvedKey resolved =
            providersService.resolveApiKey(p.getId(), t.getId());
        if (resolved != null) {
          return new ModelBinding(
              p.getId(), model, t.getId(), resolved.apiKey(), resolved.baseURL());
        }
      }
    }
    return null;
  }

  /** Processes one inbound channel message. */
  public void processInboundMessage(String channelId, InboundMessage msg) {
    AiHubChannelEntity channel = null;
    try {
      channel = channelsService.get(channelId);
      if (channel == null || !Boolean.TRUE.equals(channel.getEnabled())) {
        return;
      }

      // Dedupe: platforms redeliver webhooks; only process first sight.
      if (!channelsService.markSeen(channelId, msg.externalMessageId())) {
        return;
      }

      channelsService.updateStatus(channelId, Map.of("lastInboundAt", Instant.now().toString()));
      setTyping(channel, msg.externalChatId());

      Map<String, Object> cfg = parseJson(channel.getConfig());
      String title = channel.getName() + (msg.userName() != null ? " · " + msg.userName() : "");
      String conversationId =
          conversationsService.findOrCreateChannelConversation(
              channelId, msg.externalChatId(), title);

      List<AiHubConversationsService.ChatMessage> existing =
          conversationsService.getRecentMessages(conversationId, 1);
      String welcome = cfg.get("welcomeMessage") instanceof String w && !w.isBlank() ? w : null;
      if (existing.isEmpty() && welcome != null) {
        sendReply(channel, msg.externalChatId(), welcome);
      }

      conversationsService.addMessage(conversationId, "user", msg.text());

      int historyLimit =
          cfg.get("historyLimit") instanceof Number n && n.intValue() > 0
              ? n.intValue()
              : HISTORY_DEFAULT_LIMIT;
      List<ChatCompletionService.ChatMessage> history =
          conversationsService.getRecentMessages(conversationId, historyLimit).stream()
              .map(m -> new ChatCompletionService.ChatMessage(m.role(), m.content()))
              .toList();
      Map<String, Object> moduleSettings = moduleSettingsService.get("ai-hub");
      String cfgPrompt = cfg.get("systemPrompt") instanceof String s && !s.isBlank() ? s : null;
      String fallbackPrompt = moduleSettings.get("systemPrompt") instanceof String s ? s : null;
      String systemPrompt = cfgPrompt != null ? cfgPrompt : fallbackPrompt;

      ModelBinding binding = resolveModelBinding(cfg);
      if (binding == null) {
        throw new IllegalStateException("no enabled provider/token/model available");
      }

      String reply =
          chatService.complete(
              new ChatCompletionService.CompletionRequest(
                  binding.providerId(),
                  binding.model(),
                  binding.baseURL(),
                  binding.apiKey(),
                  history,
                  systemPrompt,
                  null,
                  null));

      conversationsService.addMessage(
          conversationId, "assistant", reply, binding.providerId(), binding.model());
      sendReply(channel, msg.externalChatId(), reply);
      // Success clears any stale error (null = JSONB key removal).
      Map<String, Object> clear = new LinkedHashMap<>();
      clear.put("lastError", null);
      clear.put("lastErrorAt", null);
      channelsService.updateStatus(channelId, clear);
    } catch (Exception err) {
      String message = err.getMessage() != null ? err.getMessage() : String.valueOf(err);
      log.error("[ai-hub] channel {} inbound failed: {}", channelId, message);
      try {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("lastError", message);
        failure.put("lastErrorAt", Instant.now().toString());
        channelsService.updateStatus(channelId, failure);
        Map<String, Object> cfg = channel != null ? parseJson(channel.getConfig()) : Map.of();
        if (channel != null && Boolean.TRUE.equals(cfg.get("errorReplyEnabled"))) {
          sendReply(
              channel, msg.externalChatId(), "Sorry — I could not process that message just now.");
        }
      } catch (Exception ignored) {
        // status/reply failures must not propagate
      }
    }
  }

  /** Sends a reply through the platform adapter. */
  public void sendReply(AiHubChannelEntity channel, String externalChatId, String text)
      throws Exception {
    ChannelCredentials creds = channelsService.getCredentials(channel);
    if (creds == null) {
      throw new IllegalStateException("channel credentials unavailable");
    }
    if ("telegram".equals(channel.getType())) {
      if (!creds.isTelegram()) {
        throw new IllegalStateException("channel credentials do not match type telegram");
      }
      telegramClient.sendReply(creds, externalChatId, text);
    } else {
      if (!creds.isWhatsapp()) {
        throw new IllegalStateException("channel credentials do not match type whatsapp");
      }
      whatsappClient.sendReply(creds, externalChatId, text);
    }
  }

  /** Best-effort typing indicator (telegram only). */
  private void setTyping(AiHubChannelEntity channel, String externalChatId) {
    if (!"telegram".equals(channel.getType())) {
      return;
    }
    ChannelCredentials creds = channelsService.getCredentials(channel);
    if (creds != null && creds.isTelegram()) {
      telegramClient.setTyping(creds, externalChatId);
    }
  }

  private String firstEnabledModel(String modelsJson) {
    try {
      var root = objectMapper.readTree(modelsJson == null ? "[]" : modelsJson);
      for (var model : root) {
        if (model.path("enabled").asBoolean(false) && !model.path("id").asText().isEmpty()) {
          return model.path("id").asText();
        }
      }
      return null;
    } catch (Exception e) {
      return null;
    }
  }

  private Map<String, Object> parseJson(String raw) {
    try {
      if (raw == null || raw.isBlank()) {
        return new LinkedHashMap<>();
      }
      return objectMapper.readValue(
          raw,
          new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception e) {
      return new LinkedHashMap<>();
    }
  }
}
