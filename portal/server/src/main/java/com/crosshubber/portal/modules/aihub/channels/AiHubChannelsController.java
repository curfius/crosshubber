package com.crosshubber.portal.modules.aihub.channels;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.config.PortalProperties;
import com.crosshubber.portal.modules.aihub.channels.dto.ChannelCredentials;
import com.crosshubber.portal.modules.aihub.pipeline.ChannelPipeline;
import com.crosshubber.portal.modules.aihub.telegram.TelegramClient;
import com.crosshubber.portal.modules.aihub.telegram.TelegramPollingService;

/**
 * Channel admin routes — mirrors {@code portal/src/modules/ai-hub/channels.routes.ts} ({@code
 * portal-ai-hub-edit}).
 */
@RestController
public class AiHubChannelsController {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final AiHubChannelsService channelsService;
  private final TelegramPollingService pollingService;
  private final TelegramClient telegramClient;
  private final ChannelPipeline pipeline;
  private final PortalProperties props;

  @Value("${portal.webhook-base-url:}")
  private String webhookBaseUrl;

  public AiHubChannelsController(
      AiHubChannelsService channelsService,
      TelegramPollingService pollingService,
      TelegramClient telegramClient,
      ChannelPipeline pipeline,
      PortalProperties props) {
    this.channelsService = channelsService;
    this.pollingService = pollingService;
    this.telegramClient = telegramClient;
    this.pipeline = pipeline;
    this.props = props;
  }

  /** Externally reachable base URL for webhook callbacks (overrideable for dev). */
  private String webhookBase() {
    String override = System.getenv("AI_HUB_WEBHOOK_BASE_URL");
    if (override != null && !override.isBlank()) {
      return override;
    }
    if (webhookBaseUrl != null && !webhookBaseUrl.isBlank()) {
      return webhookBaseUrl;
    }
    return props.getPublicBaseUrl();
  }

  private String webhookUrl(AiHubChannelEntity channel) {
    String platform = "telegram".equals(channel.getType()) ? "telegram" : "whatsapp";
    return webhookBase().replaceAll("/+$", "")
        + "/api/ai-hub/webhooks/"
        + platform
        + "/"
        + channel.getId();
  }

  private static String maskSecret(String value) {
    if (value.length() <= 8) {
      return "••••••••";
    }
    return value.substring(0, 4) + "•••" + value.substring(value.length() - 4);
  }

  private static String randomHex(int bytes) {
    byte[] buf = new byte[bytes];
    RANDOM.nextBytes(buf);
    return HexFormat.of().formatHex(buf);
  }

  /** Builds credentials from the admin form; generates the per-channel secrets. */
  private BuiltCredentials buildCredentials(String type, Map<String, Object> input) {
    if ("telegram".equals(type)) {
      String botToken = input.get("botToken") instanceof String s ? s.trim() : "";
      if (botToken.isEmpty()) {
        throw new IllegalArgumentException("botToken is required");
      }
      ChannelCredentials credentials =
          ChannelCredentials.telegram(botToken, "whsec_" + randomHex(16));
      Map<String, Object> meta = new LinkedHashMap<>();
      meta.put("botUsername", input.get("botUsername") instanceof String s ? s : "");
      meta.put("webhookSecretMasked", maskSecret(credentials.webhookSecret()));
      return new BuiltCredentials(credentials, meta);
    }
    String phoneNumberId = input.get("phoneNumberId") instanceof String s ? s.trim() : "";
    String accessToken = input.get("accessToken") instanceof String s ? s.trim() : "";
    String appSecret = input.get("appSecret") instanceof String s ? s.trim() : "";
    if (phoneNumberId.isEmpty() || accessToken.isEmpty() || appSecret.isEmpty()) {
      throw new IllegalArgumentException("phoneNumberId, accessToken and appSecret are required");
    }
    ChannelCredentials credentials =
        ChannelCredentials.whatsapp(
            phoneNumberId, accessToken, appSecret, "waverify_" + randomHex(12));
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("displayNumber", input.get("displayNumber") instanceof String s ? s : "");
    meta.put("verifyTokenMasked", maskSecret(credentials.verifyToken()));
    return new BuiltCredentials(credentials, meta);
  }

  private record BuiltCredentials(ChannelCredentials credentials, Map<String, Object> meta) {}

  @GetMapping("/api/ai-hub/channels")
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public Map<String, Object> list() {
    return Map.of("channels", channelsService.listPublic());
  }

  @PostMapping("/api/ai-hub/channels")
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public ResponseEntity<?> create(@RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> input = body == null ? Map.of() : body;
    String type = input.get("type") instanceof String s ? s : null;
    String name = input.get("name") instanceof String s ? s.trim() : "";
    String deliveryMode = "polling".equals(input.get("deliveryMode")) ? "polling" : "webhook";
    if (!"telegram".equals(type) && !"whatsapp".equals(type) || name.isEmpty()) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "type (telegram|whatsapp) and name are required"));
    }
    if ("polling".equals(deliveryMode) && !"telegram".equals(type)) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "long polling is only available for telegram channels"));
    }
    BuiltCredentials built;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> credsInput =
          input.get("credentials") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
      built = buildCredentials(type, credsInput);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> config =
        input.get("config") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    AiHubChannelEntity channel =
        channelsService.create(type, name, built.credentials(), built.meta(), config);
    Map<String, Object> pub = new LinkedHashMap<>(channelsService.toPublic(channel));
    pub.put("webhookUrl", webhookUrl(channel));
    // Only place the verify token is ever returned — for the Meta dashboard.
    if ("whatsapp".equals(type)) {
      pub.put("verifyToken", built.credentials().verifyToken());
    }
    return ResponseEntity.status(201).body(Map.of("channel", pub));
  }

  @GetMapping("/api/ai-hub/channels/{id}")
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public ResponseEntity<?> get(@PathVariable String id) {
    AiHubChannelEntity channel = channelsService.get(id);
    if (channel == null) {
      return ResponseEntity.status(404).body(Map.of("error", "channel not found"));
    }
    Map<String, Object> pub = new LinkedHashMap<>(channelsService.toPublic(channel));
    pub.put("webhookUrl", webhookUrl(channel));
    return ResponseEntity.ok(Map.of("channel", pub));
  }

  @PutMapping("/api/ai-hub/channels/{id}")
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public ResponseEntity<?> update(
      @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
    AiHubChannelEntity existing = channelsService.get(id);
    if (existing == null) {
      return ResponseEntity.status(404).body(Map.of("error", "channel not found"));
    }
    Map<String, Object> input = body == null ? Map.of() : body;
    String name = input.get("name") instanceof String s && !s.isBlank() ? s.trim() : null;
    Boolean enabled = input.get("enabled") instanceof Boolean b ? b : null;
    String deliveryMode = null;
    if ("polling".equals(input.get("deliveryMode"))
        || "webhook".equals(input.get("deliveryMode"))) {
      if (!"telegram".equals(existing.getType()) && "polling".equals(input.get("deliveryMode"))) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "long polling is only available for telegram channels"));
      }
      deliveryMode = String.valueOf(input.get("deliveryMode"));
    }
    ChannelCredentials credentials = null;
    Map<String, Object> meta = null;
    if (input.get("credentials") instanceof Map<?, ?> rawCreds) {
      try {
        @SuppressWarnings("unchecked")
        BuiltCredentials built =
            buildCredentials(existing.getType(), (Map<String, Object>) rawCreds);
        credentials = built.credentials();
        meta = built.meta();
      } catch (IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
      }
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> config =
        input.get("config") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;

    AiHubChannelEntity updated =
        channelsService.update(id, name, enabled, deliveryMode, credentials, meta, config);
    // Keep the in-process poller in sync with the persisted state.
    reconcilePoller(updated);
    Map<String, Object> pub = new LinkedHashMap<>(channelsService.toPublic(updated));
    pub.put("webhookUrl", webhookUrl(updated));
    return ResponseEntity.ok(Map.of("channel", pub));
  }

  private void reconcilePoller(AiHubChannelEntity channel) {
    if (!"telegram".equals(channel.getType())) {
      return;
    }
    if (Boolean.TRUE.equals(channel.getEnabled()) && "polling".equals(channel.getDeliveryMode())) {
      pollingService.startPoller(channel.getId());
    } else {
      pollingService.stopPoller(channel.getId());
    }
  }

  @DeleteMapping("/api/ai-hub/channels/{id}")
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public ResponseEntity<?> remove(@PathVariable String id) {
    pollingService.stopPoller(id);
    if (!channelsService.remove(id)) {
      return ResponseEntity.status(404).body(Map.of("error", "channel not found"));
    }
    return ResponseEntity.ok(Map.of("ok", true));
  }

  /** Registers the webhook with the platform (Telegram only). */
  @PostMapping("/api/ai-hub/channels/{id}/register-webhook")
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public ResponseEntity<?> registerWebhook(@PathVariable String id) {
    AiHubChannelEntity channel = channelsService.get(id);
    if (channel == null) {
      return ResponseEntity.status(404).body(Map.of("error", "channel not found"));
    }
    if (!"telegram".equals(channel.getType())) {
      return ResponseEntity.badRequest()
          .body(
              Map.of(
                  "error",
                  "webhook registration is automatic for telegram only;"
                      + " configure the Meta dashboard with the displayed URL and verify token"));
    }
    if ("polling".equals(channel.getDeliveryMode())) {
      return ResponseEntity.badRequest()
          .body(
              Map.of(
                  "error",
                  "channel is in long-polling mode — switch delivery mode to" + " webhook first"));
    }
    var creds = channelsService.getCredentials(channel);
    if (creds == null || !creds.isTelegram()) {
      return ResponseEntity.badRequest().body(Map.of("error", "channel credentials missing"));
    }
    String url = webhookUrl(channel);
    pollingService.stopPoller(channel.getId());
    try {
      telegramClient.registerWebhook(creds, url);
      Map<String, Object> status = new LinkedHashMap<>();
      status.put("webhookUrl", url);
      status.put("lastError", null);
      status.put("lastErrorAt", null);
      channelsService.updateStatus(channel.getId(), status);
      return ResponseEntity.ok(Map.of("ok", true, "webhookUrl", url));
    } catch (Exception e) {
      return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
    }
  }

  /** Sends a message to an external chat (outbound). */
  @PostMapping("/api/ai-hub/channels/{id}/send")
  @PreAuthorize("hasRole('portal-ai-hub-edit')")
  public ResponseEntity<?> send(
      @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> input = body == null ? Map.of() : body;
    String chatId = input.get("chatId") instanceof String s ? s.trim() : "";
    String text = input.get("text") instanceof String s ? s : "";
    if (chatId.isEmpty() || text.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "chatId and text are required"));
    }
    AiHubChannelEntity channel = channelsService.get(id);
    if (channel == null) {
      return ResponseEntity.status(404).body(Map.of("error", "channel not found"));
    }
    try {
      pipeline.sendReply(channel, chatId, text);
      return ResponseEntity.ok(Map.of("ok", true));
    } catch (Exception e) {
      String msg = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
      // Meta 131047 / 24976: re-engagement required — outside the 24h window.
      if (msg.contains("131047") || msg.contains("24976")) {
        return ResponseEntity.unprocessableEntity()
            .body(
                Map.of(
                    "error",
                    "outside the 24h customer service window — the user must message first"
                        + " (template messages are not supported yet)"));
      }
      return ResponseEntity.status(502).body(Map.of("error", msg));
    }
  }
}
