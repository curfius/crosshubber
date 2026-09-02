package com.crosshubber.portal.modules.aihub.webhooks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crosshubber.portal.modules.aihub.channels.AiHubChannelEntity;
import com.crosshubber.portal.modules.aihub.channels.AiHubChannelsService;
import com.crosshubber.portal.modules.aihub.channels.dto.ChannelCredentials;
import com.crosshubber.portal.modules.aihub.pipeline.ChannelPipeline;
import com.crosshubber.portal.modules.aihub.telegram.TelegramClient;
import com.crosshubber.portal.modules.aihub.whatsapp.WhatsappClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Public channel webhooks (NO cookie auth — platform-secret validated) — mirrors {@code
 * portal/src/modules/ai-hub/channels.webhook.routes.ts}. Both endpoints respond 200 quickly and
 * process asynchronously so the platform never waits for the LLM. The WhatsApp POST signature check
 * needs the RAW body, read directly from the request input stream.
 */
@RestController
public class AiHubWebhookController {

  private static final Logger log = LoggerFactory.getLogger(AiHubWebhookController.class);
  private static final int MAX_BODY_BYTES = 256 * 1024;

  private final AiHubChannelsService channelsService;
  private final TelegramClient telegramClient;
  private final WhatsappClient whatsappClient;
  private final ChannelPipeline pipeline;
  private final ObjectMapper objectMapper;
  private final ExecutorService processingExecutor;

  public AiHubWebhookController(
      AiHubChannelsService channelsService,
      TelegramClient telegramClient,
      WhatsappClient whatsappClient,
      ChannelPipeline pipeline,
      ObjectMapper objectMapper) {
    this.channelsService = channelsService;
    this.telegramClient = telegramClient;
    this.whatsappClient = whatsappClient;
    this.pipeline = pipeline;
    this.objectMapper = objectMapper;
    this.processingExecutor =
        Executors.newFixedThreadPool(
            4,
            r -> {
              Thread thread = new Thread(r, "aihub-webhook");
              thread.setDaemon(true);
              return thread;
            });
  }

  // ── Telegram ─────────────────────────────────────────────────────────

  @PostMapping("/api/ai-hub/webhooks/telegram/{channelId}")
  public ResponseEntity<?> telegram(@PathVariable String channelId, HttpServletRequest request)
      throws IOException {
    // Body parsing happens BEFORE any handler checks in Node (express.json middleware) — a
    // malformed body must 500 regardless of channel existence/secret, so parse first.
    Object payload = readJson(request);
    if (payload == null) {
      // Mirrors Node: express.json() SyntaxError funnels into the global errorHandler →
      // 500 {"error":"internal server error"} (middleware/errors.ts ignores the 400 status).
      return ResponseEntity.status(500).body(Map.of("error", "internal server error"));
    }
    AiHubChannelEntity channel = channelsService.get(channelId);
    if (channel == null || !"telegram".equals(channel.getType())) {
      return ResponseEntity.status(404).body(Map.of("error", "not found"));
    }
    ChannelCredentials creds = channelsService.getCredentials(channel);
    if (creds == null || !creds.isTelegram()) {
      return ResponseEntity.status(500).body(Map.of("error", "channel misconfigured"));
    }
    String secret = request.getHeader("x-telegram-bot-api-secret-token");
    if (!telegramClient.verifyWebhookSecret(secret, creds)) {
      return ResponseEntity.status(401).body(Map.of("error", "invalid secret token"));
    }
    // Valid JSON that is not an object (e.g. an array) parses fine in Node and yields no inbound
    // message → 200 {ok:true}.
    ChannelPipeline.InboundMessage msg =
        payload instanceof Map<?, ?> map
            ? toPipeline(telegramClient.parseInbound(asStringMap(map)))
            : null;
    return handleInbound(channelId, msg);
  }

  // ── WhatsApp ─────────────────────────────────────────────────────────

  @GetMapping("/api/ai-hub/webhooks/whatsapp/{channelId}")
  public ResponseEntity<?> whatsappVerify(
      @PathVariable String channelId, HttpServletRequest request) {
    AiHubChannelEntity channel = channelsService.get(channelId);
    if (channel == null || !"whatsapp".equals(channel.getType())) {
      return ResponseEntity.status(404).body(Map.of("error", "not found"));
    }
    ChannelCredentials creds = channelsService.getCredentials(channel);
    if (creds == null || !creds.isWhatsapp()) {
      return ResponseEntity.status(500).body(Map.of("error", "channel misconfigured"));
    }
    String challenge =
        whatsappClient.verifyChallenge(
            request.getParameter("hub.mode"),
            request.getParameter("hub.verify_token"),
            request.getParameter("hub.challenge"),
            creds);
    if (challenge == null) {
      return ResponseEntity.status(403).body(Map.of("error", "verification failed"));
    }
    return ResponseEntity.ok().header("Content-Type", "text/plain").body(challenge);
  }

  @PostMapping("/api/ai-hub/webhooks/whatsapp/{channelId}")
  public ResponseEntity<?> whatsapp(@PathVariable String channelId, HttpServletRequest request)
      throws IOException {
    AiHubChannelEntity channel = channelsService.get(channelId);
    if (channel == null || !"whatsapp".equals(channel.getType())) {
      return ResponseEntity.status(404).body(Map.of("error", "not found"));
    }
    ChannelCredentials creds = channelsService.getCredentials(channel);
    if (creds == null || !creds.isWhatsapp()) {
      return ResponseEntity.status(500).body(Map.of("error", "channel misconfigured"));
    }
    String rawBody = readRawBody(request);
    if (!whatsappClient.verifySignature(rawBody, request.getHeader("x-hub-signature-256"), creds)) {
      return ResponseEntity.status(401).body(Map.of("error", "invalid signature"));
    }
    Map<String, Object> payload;
    try {
      payload =
          objectMapper.readValue(
              rawBody, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    } catch (Exception parseFailure) {
      return ResponseEntity.ok(Map.of("ok", true));
    }
    ChannelPipeline.InboundMessage msg = toPipeline(whatsappClient.parseInbound(payload));
    return handleInbound(channelId, msg);
  }

  // ── Helpers ──────────────────────────────────────────────────────────

  /** Responds 200 immediately, then processes fire-and-forget. */
  private ResponseEntity<?> handleInbound(String channelId, ChannelPipeline.InboundMessage msg) {
    if (msg == null || msg.text().trim().isEmpty()) {
      return ResponseEntity.ok(Map.of("ok", true));
    }
    ResponseEntity<?> response = ResponseEntity.ok(Map.of("ok", true));
    processingExecutor.submit(() -> pipeline.processInboundMessage(channelId, msg));
    return response;
  }

  private ChannelPipeline.InboundMessage toPipeline(TelegramClient.InboundMessage inbound) {
    if (inbound == null) {
      return null;
    }
    return new ChannelPipeline.InboundMessage(
        inbound.externalChatId(), inbound.externalMessageId(), inbound.text(), inbound.userName());
  }

  private ChannelPipeline.InboundMessage toPipeline(WhatsappClient.InboundMessage inbound) {
    if (inbound == null) {
      return null;
    }
    return new ChannelPipeline.InboundMessage(
        inbound.externalChatId(), inbound.externalMessageId(), inbound.text(), inbound.userName());
  }

  private String readRawBody(HttpServletRequest request) throws IOException {
    byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    if (bytes.length > MAX_BODY_BYTES) {
      throw new IOException("body too large");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  @SuppressWarnings("unchecked")
  private Object readJson(HttpServletRequest request) {
    try {
      String raw = readRawBody(request);
      return objectMapper.readValue(raw, Object.class);
    } catch (Exception e) {
      log.debug("[ai-hub] webhook body not JSON: {}", e.getMessage());
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asStringMap(Object map) {
    return (Map<String, Object>) map;
  }
}
