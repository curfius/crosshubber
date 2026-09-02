package com.crosshubber.portal.modules.aihub.telegram;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.modules.aihub.channels.dto.ChannelCredentials;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Telegram Bot API adapter — mirrors {@code portal/src/modules/ai-hub/channels/telegram.ts}. */
@Service
public class TelegramClient {

  private static final String API_BASE = "https://api.telegram.org";
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public TelegramClient(ObjectMapper objectMapper) {
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    this.objectMapper = objectMapper;
  }

  /** Thrown when getUpdates hits 409 (a webhook is still registered). */
  public static class TelegramConflictError extends RuntimeException {

    public TelegramConflictError() {
      super("webhook still registered — deleteWebhook required before polling");
    }
  }

  public record InboundMessage(
      String externalChatId, String externalMessageId, String text, String userName) {}

  public record TelegramUpdate(long updateId, InboundMessage message) {}

  private String apiUrl(ChannelCredentials credentials, String method) {
    return API_BASE + "/bot" + credentials.botToken() + "/" + method;
  }

  /** Timing-safe comparison of the X-Telegram-Bot-Api-Secret-Token header. */
  public boolean verifyWebhookSecret(String received, ChannelCredentials credentials) {
    if (received == null || credentials.webhookSecret() == null) {
      return false;
    }
    return MessageDigest.isEqual(
        received.getBytes(StandardCharsets.UTF_8),
        credentials.webhookSecret().getBytes(StandardCharsets.UTF_8));
  }

  /** Extracts the first text message from a webhook update; null for others. */
  public InboundMessage parseInbound(Map<String, Object> payload) {
    Object messageObj = payload == null ? null : payload.get("message");
    if (!(messageObj instanceof Map<?, ?> msg)) {
      return null;
    }
    Object text = msg.get("text");
    Object chat = msg.get("chat");
    Object messageId = msg.get("message_id");
    if (!(text instanceof String textStr)
        || textStr.isEmpty()
        || !(chat instanceof Map<?, ?> chatMap)
        || chatMap.get("id") == null
        || messageId == null) {
      return null;
    }
    String userName = null;
    if (msg.get("from") instanceof Map<?, ?> from) {
      String first = from.get("first_name") instanceof String s ? s : null;
      String last = from.get("last_name") instanceof String s ? s : null;
      String username = from.get("username") instanceof String s ? s : null;
      if (first != null || last != null) {
        userName = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
      }
      if (userName == null || userName.isEmpty()) {
        userName = username;
      }
    }
    return new InboundMessage(
        String.valueOf(chatMap.get("id")),
        String.valueOf(messageId),
        textStr,
        userName == null || userName.isEmpty() ? null : userName);
  }

  /** Extracts the first text message from a getUpdates poll update. */
  public InboundMessage parseUpdate(TelegramUpdate update) {
    return update.message();
  }

  private void callApi(ChannelCredentials credentials, String method, Map<String, Object> body)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl(credentials, method)))
            .timeout(CALL_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException(
          "telegram "
              + method
              + " failed: "
              + response.statusCode()
              + " "
              + truncate(response.body(), 200));
    }
  }

  public void sendReply(ChannelCredentials credentials, String chatId, String text)
      throws Exception {
    callApi(
        credentials,
        "sendMessage",
        Map.of(
            "chat_id", chatId,
            "text", text,
            "link_preview_options", Map.of("is_disabled", true)));
  }

  /** Typing indicator — best-effort only, never throws. */
  public void setTyping(ChannelCredentials credentials, String chatId) {
    try {
      callApi(credentials, "sendChatAction", Map.of("chat_id", chatId, "action", "typing"));
    } catch (Exception ignored) {
      // best-effort
    }
  }

  /** Registers the webhook with Telegram (secret-token protected). */
  public void registerWebhook(ChannelCredentials credentials, String url) throws Exception {
    callApi(
        credentials,
        "setWebhook",
        Map.of(
            "url",
            url,
            "secret_token",
            credentials.webhookSecret(),
            "allowed_updates",
            List.of("message"),
            "drop_pending_updates",
            false));
  }

  /** Removes a registered webhook — required before long polling can start. */
  public void deleteWebhook(ChannelCredentials credentials) throws Exception {
    callApi(credentials, "deleteWebhook", Map.of("drop_pending_updates", false));
  }

  /**
   * One long-poll {@code getUpdates} cycle — resolves with pending updates (empty on timeout).
   * Throws {@link TelegramConflictError} on 409.
   */
  public List<TelegramUpdate> getUpdates(
      ChannelCredentials credentials, long offset, int timeoutSec) throws Exception {
    String allowed = java.net.URLEncoder.encode("[\"message\"]", StandardCharsets.UTF_8);
    String url =
        apiUrl(credentials, "getUpdates")
            + "?offset="
            + offset
            + "&timeout="
            + timeoutSec
            + "&allowed_updates="
            + allowed;
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSec + 10))
            .GET()
            .build();
    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw e;
    }
    if (response.statusCode() == 409) {
      throw new TelegramConflictError();
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException(
          "telegram getUpdates failed: "
              + response.statusCode()
              + " "
              + truncate(response.body(), 200));
    }
    return parseUpdates(response.body());
  }

  private List<TelegramUpdate> parseUpdates(String json) {
    List<TelegramUpdate> updates = new ArrayList<>();
    try {
      JsonNode root = objectMapper.readTree(json);
      for (var update : root.path("result")) {
        long updateId = update.path("update_id").asLong(0);
        InboundMessage message =
            parseInbound(
                objectMapper.convertValue(update, new TypeReference<Map<String, Object>>() {}));
        updates.add(new TelegramUpdate(updateId, message));
      }
    } catch (Exception ignored) {
      // empty result on parse failure
    }
    return updates;
  }

  private String toJson(Map<String, Object> body) {
    try {
      return objectMapper.writeValueAsString(body);
    } catch (Exception e) {
      throw new IllegalStateException("telegram request serialization failed", e);
    }
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
