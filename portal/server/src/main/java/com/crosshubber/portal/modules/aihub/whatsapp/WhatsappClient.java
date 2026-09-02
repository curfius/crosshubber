package com.crosshubber.portal.modules.aihub.whatsapp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.crosshubber.portal.modules.aihub.channels.dto.ChannelCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Meta WhatsApp Cloud API adapter — mirrors {@code portal/src/modules/ai-hub/channels/whatsapp.ts}.
 */
@Service
public class WhatsappClient {

  private static final String GRAPH_BASE = "https://graph.facebook.com/v21.0";
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public WhatsappClient(ObjectMapper objectMapper) {
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    this.objectMapper = objectMapper;
  }

  public record InboundMessage(
      String externalChatId, String externalMessageId, String text, String userName) {}

  /**
   * Timing-safe check of the X-Hub-Signature-256 header ("sha256=&lt;hex&gt;") against the raw
   * body.
   */
  public boolean verifySignature(
      String rawBody, String signatureHeader, ChannelCredentials credentials) {
    if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
      return false;
    }
    String received = signatureHeader.substring("sha256=".length());
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(
              credentials.appSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : digest) {
        hex.append(String.format("%02x", b));
      }
      return MessageDigest.isEqual(
          received.getBytes(StandardCharsets.UTF_8),
          hex.toString().getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      return false;
    }
  }

  /** Returns the challenge string for Meta's verification handshake, or null. */
  public String verifyChallenge(
      String hubMode, String hubToken, String hubChallenge, ChannelCredentials credentials) {
    if ("subscribe".equals(hubMode)
        && credentials.verifyToken() != null
        && credentials.verifyToken().equals(hubToken)
        && hubChallenge != null) {
      return hubChallenge;
    }
    return null;
  }

  /** Extracts the first inbound text message; null for status/non-text updates. */
  @SuppressWarnings("unchecked")
  public InboundMessage parseInbound(Map<String, Object> payload) {
    try {
      List<Map<String, Object>> entry = (List<Map<String, Object>>) payload.get("entry");
      if (entry == null || entry.isEmpty()) {
        return null;
      }
      List<Map<String, Object>> changes = (List<Map<String, Object>>) entry.get(0).get("changes");
      if (changes == null || changes.isEmpty()) {
        return null;
      }
      Map<String, Object> value = (Map<String, Object>) changes.get(0).get("value");
      if (value == null) {
        return null;
      }
      List<Map<String, Object>> messages = (List<Map<String, Object>>) value.get("messages");
      if (messages == null || messages.isEmpty()) {
        return null;
      }
      Map<String, Object> message = messages.get(0);
      if (!"text".equals(message.get("type"))) {
        return null;
      }
      Map<String, Object> text = (Map<String, Object>) message.get("text");
      if (text == null
          || text.get("body") == null
          || message.get("from") == null
          || message.get("id") == null) {
        return null;
      }
      String userName = null;
      if (value.get("contacts") instanceof List<?> contacts
          && !contacts.isEmpty()
          && contacts.get(0) instanceof Map<?, ?> contact
          && contact.get("profile") instanceof Map<?, ?> profile
          && profile.get("name") instanceof String name) {
        userName = name;
      }
      return new InboundMessage(
          String.valueOf(message.get("from")),
          String.valueOf(message.get("id")),
          String.valueOf(text.get("body")),
          userName == null || userName.isEmpty() ? null : userName);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Sends a reply. Meta error 131047 (re-engagement required) surfaces in the message so callers
   * can map it to 422.
   */
  public void sendReply(ChannelCredentials credentials, String chatId, String text)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(GRAPH_BASE + "/" + credentials.phoneNumberId() + "/messages"))
            .timeout(CALL_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + credentials.accessToken())
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    toJson(
                        Map.of(
                            "messaging_product", "whatsapp",
                            "recipient_type", "individual",
                            "to", chatId,
                            "type", "text",
                            "text", Map.of("body", text))),
                    StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException(
          "whatsapp send failed: " + response.statusCode() + " " + truncate(response.body(), 300));
    }
  }

  private String toJson(Map<String, Object> body) {
    try {
      return objectMapper.writeValueAsString(body);
    } catch (Exception e) {
      throw new IllegalStateException("whatsapp request serialization failed", e);
    }
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
