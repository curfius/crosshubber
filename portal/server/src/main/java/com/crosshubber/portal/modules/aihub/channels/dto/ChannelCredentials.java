package com.crosshubber.portal.modules.aihub.channels.dto;

import java.util.Map;

/**
 * Channel platform credentials (decrypted at runtime only) — mirrors {@code ChannelCredentials} in
 * channels/types.ts. Telegram channels carry {@code botToken}/{@code webhookSecret}; WhatsApp
 * channels carry {@code phoneNumberId}/{@code accessToken}/{@code appSecret}/{@code verifyToken}.
 */
public final class ChannelCredentials {

  private final Map<String, Object> values;

  private ChannelCredentials(Map<String, Object> values) {
    this.values = values;
  }

  public static ChannelCredentials from(Map<String, Object> values) {
    return new ChannelCredentials(values);
  }

  public static ChannelCredentials telegram(String botToken, String webhookSecret) {
    return new ChannelCredentials(
        Map.of(
            "botToken", botToken,
            "webhookSecret", webhookSecret));
  }

  public static ChannelCredentials whatsapp(
      String phoneNumberId, String accessToken, String appSecret, String verifyToken) {
    return new ChannelCredentials(
        Map.of(
            "phoneNumberId", phoneNumberId,
            "accessToken", accessToken,
            "appSecret", appSecret,
            "verifyToken", verifyToken));
  }

  public Map<String, Object> asMap() {
    return values;
  }

  public String botToken() {
    return str("botToken");
  }

  public String webhookSecret() {
    return str("webhookSecret");
  }

  public String phoneNumberId() {
    return str("phoneNumberId");
  }

  public String accessToken() {
    return str("accessToken");
  }

  public String appSecret() {
    return str("appSecret");
  }

  public String verifyToken() {
    return str("verifyToken");
  }

  public boolean isTelegram() {
    return values.containsKey("botToken");
  }

  public boolean isWhatsapp() {
    return values.containsKey("phoneNumberId");
  }

  private String str(String key) {
    return values.get(key) instanceof String s ? s : null;
  }
}
